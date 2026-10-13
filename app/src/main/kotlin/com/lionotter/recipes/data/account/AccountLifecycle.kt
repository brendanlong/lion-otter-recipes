package com.lionotter.recipes.data.account

import com.google.firebase.auth.AuthCredential
import com.lionotter.recipes.data.image.PendingImageUploads
import com.lionotter.recipes.data.local.LocalDataScope
import com.lionotter.recipes.data.local.PerUserLocalData
import com.lionotter.recipes.data.remote.AccountDeletionService
import com.lionotter.recipes.data.remote.AccountTransition
import com.lionotter.recipes.data.remote.AuthSession
import com.lionotter.recipes.data.remote.AuthState
import com.lionotter.recipes.data.remote.DataFailure
import com.lionotter.recipes.data.remote.DataOp
import com.lionotter.recipes.data.remote.FirestoreCache
import com.lionotter.recipes.data.remote.RemoteAccountDeletion
import com.lionotter.recipes.util.ErrorLog
import com.lionotter.recipes.util.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of [AccountLifecycle.signOut]. */
sealed class SignOutResult {
    data object Success : SignOutResult()

    /**
     * Some local changes have not reached the server yet; signing out now would
     * discard them. Nothing was changed. Call [AccountLifecycle.signOut] with
     * `force = true` to sign out anyway.
     */
    data object PendingWrites : SignOutResult()
}

/**
 * Account transitions: a guest signing in with Google, signing out, and deleting the account.
 * Composes Firebase Auth ([AuthSession]), the server data ([RemoteAccountDeletion]), Firestore's
 * on-device cache ([FirestoreCache]) and every other per-user store on the device ([localData],
 * the `Set<PerUserLocalData>` registry).
 *
 * Transitions are serialized, and the auth state is [AuthState.Loading] throughout each one and
 * always ends as a real state. Nothing meant for an account that leaves the device (sign-out,
 * deletion) ends up in the next one: its background work ([work]) is cancelled and awaited while
 * it is still signed in, so a save under way goes to it and is cleared with it, and writes that
 * arrive during the transition are dropped ([AccountTransition.LEAVE]). During a guest's sign-in
 * they wait and go to the resulting account instead ([AccountTransition.MERGE]).
 *
 * A store that fails to clear never stops the transition: the user still ends up signed out, and
 * is told that some of the previous account's data may remain.
 */
@Singleton
class AccountLifecycle @Inject constructor(
    private val auth: AuthSession,
    private val firestoreCache: FirestoreCache,
    private val remoteDeletion: RemoteAccountDeletion,
    private val pendingImageUploads: PendingImageUploads,
    private val work: AccountBoundWork,
    private val localData: Set<@JvmSuppressWildcards PerUserLocalData>,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "AccountLifecycle"
        private const val PENDING_WRITES_TIMEOUT_MS = 10_000L
        private const val WORK_STOP_TIMEOUT_MS = 30_000L
    }

    private val transitionMutex = Mutex()

    /**
     * Moves from guest mode to a Google account: [migrate] copies the guest's data
     * (identified by the guest UID it is given) to the Google account and signs the
     * Google user in on the default Firebase app; then the guest's Firestore cache is cleared.
     * The other per-user data stays: it now belongs to the Google account (local images are
     * uploaded afterwards, and meal plans keep their IDs).
     *
     * Repos have no listeners while Loading, so none is left on the guest path when the
     * network is enabled (which would fail with PERMISSION_DENIED).
     *
     * If [migrate] fails, the guest state is restored with its data intact.
     */
    suspend fun mergeGuestIntoGoogle(migrate: suspend (guestUid: String) -> Result<Unit>): Result<Unit> =
        transitionMutex.withLock {
            val guestUid = (auth.authState.value as? AuthState.Guest)?.uid
                ?: return@withLock Result.failure(IllegalStateException("Not in guest mode"))
            auth.beginTransition(AccountTransition.MERGE)
            try {
                val result = migrate(guestUid)
                if (result.isSuccess) {
                    val state = auth.resolveState()
                    val cleared = firestoreCache.clearLocalData(enableNetwork = state is AuthState.Google)
                    auth.endTransition(state)
                    if (!cleared) firestoreCache.reportFailure(DataFailure(DataOp.CLEAR_PREVIOUS_ACCOUNT))
                }
                result
            } finally {
                endTransitionIfStillLoading()
            }
        }

    /**
     * Signs out the current Google user and transitions to a fresh guest, clearing the
     * account's data on the device ([LocalDataScope.ACCOUNT]).
     *
     * Unless [force] is set, first waits for pending writes to reach the server and
     * returns [SignOutResult.PendingWrites] (without signing out) if they don't, or if
     * some recipe images haven't been uploaded yet, so the UI can warn that unsynced
     * changes would be lost.
     */
    suspend fun signOut(force: Boolean = false): SignOutResult = transitionMutex.withLock {
        if (!force && auth.authState.value is AuthState.Google) {
            if (!firestoreCache.awaitPendingWrites(PENDING_WRITES_TIMEOUT_MS)) {
                ErrorLog.w(TAG, "Sign-out aborted: local changes have not synced yet")
                return@withLock SignOutResult.PendingWrites
            }
            if (pendingImageUploads.hasPendingLocalImages()) {
                ErrorLog.w(TAG, "Sign-out aborted: some recipe images have not been uploaded yet")
                return@withLock SignOutResult.PendingWrites
            }
        }
        val scopes = setOf(LocalDataScope.ACCOUNT)
        stopBackgroundWork(scopes)
        auth.beginTransition(AccountTransition.LEAVE)
        try {
            resetToFreshGuest(scopes)
        } finally {
            endTransitionIfStillLoading()
        }
        SignOutResult.Success
    }

    /**
     * Deletes the user's server data and Firebase Auth account, then everything the app keeps on
     * the device for them (every [LocalDataScope]), ending as a fresh guest. The server goes
     * first, so a failure there leaves the user signed in with their data intact (background
     * work such as imports has been stopped, though; failed imports can be retried).
     *
     * @param reauthCredential A fresh Google credential (Firebase requires a recent login to
     *        delete an account). If null and the last sign-in is not recent,
     *        [com.lionotter.recipes.data.remote.ReauthenticationRequiredException] is thrown
     *        before anything is deleted.
     * @throws com.lionotter.recipes.data.remote.ReauthenticationRequiredException if re-authentication is needed
     * @throws AccountDeletionService.OfflineException if the server can't be reached
     * @throws AccountDeletionService.AuthAccountDeletionException if user data was deleted
     *         but the Firebase Auth account could not be. The app is still signed out.
     */
    suspend fun deleteAccountAndData(reauthCredential: AuthCredential? = null) {
        transitionMutex.withLock {
            val scopes = LocalDataScope.entries.toSet()
            val previousState = auth.authState.value
            stopBackgroundWork(scopes)
            auth.beginTransition(AccountTransition.LEAVE)
            try {
                try {
                    remoteDeletion.deleteAllUserData(reauthCredential)
                } catch (e: AccountDeletionService.AuthAccountDeletionException) {
                    ErrorLog.e(TAG, "User data deleted but auth account deletion failed", e)
                    resetToFreshGuest(scopes)
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ErrorLog.e(TAG, "Failed to delete account and data", e)
                    auth.endTransition(previousState)
                    throw e
                }
                resetToFreshGuest(scopes)
            } finally {
                endTransitionIfStillLoading()
            }
        }
    }

    /**
     * Clears the Firestore cache and the per-user data in [scopes], then signs out of Firebase
     * and publishes a fresh guest. The sign-out happens even if clearing fails or is cancelled.
     * If the sign-out itself fails, the (still signed-in) user is published with the network back
     * on, and the failure is rethrown. Caller must hold [transitionMutex] and have begun a transition.
     */
    private suspend fun resetToFreshGuest(scopes: Set<LocalDataScope>) {
        var cleared = false
        try {
            val firestoreCleared = firestoreCache.clearLocalData(enableNetwork = false)
            cleared = clearLocalData(scopes) && firestoreCleared
        } finally {
            withContext(NonCancellable) {
                val signOutFailure = try {
                    auth.signOutAndForgetGuest()
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ErrorLog.e(TAG, "Failed to sign out of Firebase", e)
                    e
                }
                val state = auth.resolveState()
                if (state is AuthState.Google) firestoreCache.enableNetwork()
                auth.endTransition(state)
                if (!cleared) firestoreCache.reportFailure(DataFailure(DataOp.CLEAR_PREVIOUS_ACCOUNT))
                signOutFailure?.let { throw it }
            }
        }
    }

    /**
     * Asks the stores in [scopes] to stop their background work and cancels the [work] that saves
     * into the account, then waits (up to [WORK_STOP_TIMEOUT_MS]) for running workers to finish,
     * so their last saves go to the outgoing account. Failures are logged.
     */
    private suspend fun stopBackgroundWork(scopes: Set<LocalDataScope>) {
        forEachStore(scopes, "stop background work of") { it.stopBackgroundWork() }
        cancelWork()
        if (withTimeoutOrNull(WORK_STOP_TIMEOUT_MS) { work.awaitStopped() } == null) {
            ErrorLog.w(TAG, "Background work didn't stop in time; continuing anyway")
        }
        // Work enqueued while waiting (the UI is still live)
        cancelWork()
    }

    private fun cancelWork() {
        try {
            work.cancelAll()
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Failed to cancel background work", e)
        }
    }

    /** Clears every registered store in [scopes]; one failing doesn't stop the others. Returns whether all succeeded. */
    private suspend fun clearLocalData(scopes: Set<LocalDataScope>): Boolean =
        forEachStore(scopes, "clear") { it.clearUserData() }

    /** Runs [action] on each store in [scopes], logging failures. Returns whether all succeeded. */
    private suspend fun forEachStore(
        scopes: Set<LocalDataScope>,
        description: String,
        action: suspend (PerUserLocalData) -> Unit
    ): Boolean = withContext(ioDispatcher) {
        var allSucceeded = true
        for (store in localData.filter { it.scope in scopes }) {
            try {
                action(store)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Failed to $description ${store.javaClass.simpleName}", e)
                allSucceeded = false
            }
        }
        allSucceeded
    }

    private fun endTransitionIfStillLoading() {
        if (auth.authState.value is AuthState.Loading) auth.endTransition(auth.resolveState())
    }
}
