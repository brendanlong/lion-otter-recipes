package com.lionotter.recipes.data.remote

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.Firebase
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import com.lionotter.recipes.R
import com.lionotter.recipes.util.ErrorLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Represents the current authentication state of the app. */
sealed class AuthState {
    /** Transitioning between auth states (sign-in migration, sign-out, account deletion). */
    data object Loading : AuthState()

    /** User is in guest mode (offline-only, no cloud sync). Uses a local UUID. */
    data class Guest(val uid: String) : AuthState()

    /** User is signed in with a Google account. */
    data class Google(val uid: String, val email: String) : AuthState()
}

/** The current user's UID, or null if loading. */
val AuthState.uid: String?
    get() = when (this) {
        is AuthState.Loading -> null
        is AuthState.Guest -> uid
        is AuthState.Google -> uid
    }

/** Outcome of [AuthService.signOut]. */
sealed class SignOutResult {
    data object Success : SignOutResult()

    /**
     * Some local changes have not reached the server yet; signing out now would
     * discard them. Nothing was changed. Call [AuthService.signOut] with
     * `force = true` to sign out anyway.
     */
    data object PendingWrites : SignOutResult()
}

/**
 * Thrown by [AuthService.deleteAccountAndData] when the Google account must be
 * re-authenticated before deletion. Nothing has been deleted. Retry with an
 * Activity context so a fresh Google credential can be requested.
 */
class ReauthenticationRequiredException(cause: Throwable? = null) :
    Exception("Please sign in again to delete your account", cause)

/**
 * Owns the app's authentication state.
 *
 * The state is resolved synchronously on construction (from the persisted
 * Firebase Auth user), so it is never [AuthState.Loading] at startup — even in a
 * process started only to run a WorkManager job. [FirestoreService] independently
 * disables the network for guests before Firestore is first used.
 */
@Singleton
class AuthService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val firestoreService: FirestoreService,
    private val accountDeletionService: AccountDeletionService,
    private val imageSyncService: ImageSyncService
) {
    companion object {
        private const val TAG = "AuthService"
        private const val PREFS_NAME = "auth_prefs"
        private const val KEY_GUEST_UID = "guest_uid"
        private const val PENDING_WRITES_TIMEOUT_MS = 10_000L
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Serializes auth transitions so sign-out, deletion and merge cannot interleave. */
    private val transitionMutex = Mutex()

    private val _authState = MutableStateFlow(resolveCurrentState())
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    /** Whether the current user is signed in with Google (not a guest). */
    fun isGoogleUser(): Boolean = _authState.value is AuthState.Google

    /**
     * Returns the current UID, waiting for an in-progress auth transition (which always
     * ends, successfully or not) to finish.
     */
    suspend fun awaitUid(): String =
        _authState.first { it !is AuthState.Loading }.uid
            ?: throw IllegalStateException("User not authenticated")

    /**
     * Gets a Google credential for the sign-in button flow. Always uses the migration
     * flow to copy guest data to the Google account since the guest UID is local-only.
     *
     * A failure with [GetCredentialCancellationException] means the user dismissed the
     * account picker and should not be reported as an error.
     *
     * @return The Google [AuthCredential] for use by [AccountMigrationService]
     */
    suspend fun signInWithGoogle(activityContext: Context): Result<AuthCredential> {
        return try {
            val option = GetSignInWithGoogleOption.Builder(webClientId()).build()
            Result.success(getGoogleCredential(activityContext, GetCredentialRequest.Builder().addCredentialOption(option).build()))
        } catch (e: GetCredentialCancellationException) {
            Log.i(TAG, "Google sign-in cancelled by user")
            Result.failure(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Google sign-in failed", e)
            Result.failure(e)
        }
    }

    /**
     * Moves from guest mode to a Google account: [migrate] copies the guest's data
     * (identified by the guest UID it is given) to the Google account and signs the
     * Google user in on the default Firebase app; then the guest cache is cleared.
     *
     * The state is [AuthState.Loading] throughout, so writes made meanwhile (e.g. a
     * worker finishing an import) wait and then go to the Google account, instead of
     * landing in the guest cache after it was copied and being wiped with it. Repos have
     * no listeners while Loading, so none is left on the guest path when the network is
     * enabled (which would fail with PERMISSION_DENIED).
     *
     * If [migrate] fails, the guest state is restored with its data intact.
     */
    suspend fun mergeGuestIntoGoogle(migrate: suspend (guestUid: String) -> Result<Unit>): Result<Unit> =
        transitionMutex.withLock {
            val guestUid = (_authState.value as? AuthState.Guest)?.uid
                ?: return@withLock Result.failure(IllegalStateException("Not in guest mode"))
            _authState.value = AuthState.Loading
            try {
                val result = migrate(guestUid)
                if (result.isSuccess) {
                    val state = resolveCurrentState()
                    clearLocalData(enableNetwork = state is AuthState.Google)
                    _authState.value = state
                }
                result
            } finally {
                if (_authState.value is AuthState.Loading) _authState.value = resolveCurrentState()
            }
        }

    /**
     * Signs out the current Google user and transitions to guest mode.
     * Clears Firestore persistence and generates a new guest UID.
     *
     * Unless [force] is set, first waits for pending writes to reach the server and
     * returns [SignOutResult.PendingWrites] (without signing out) if they don't, or if
     * some recipe images haven't been uploaded yet, so the UI can warn that unsynced
     * changes would be lost.
     */
    suspend fun signOut(force: Boolean = false): SignOutResult = transitionMutex.withLock {
        if (!force && _authState.value is AuthState.Google) {
            if (!firestoreService.awaitPendingWrites(PENDING_WRITES_TIMEOUT_MS)) {
                Log.w(TAG, "Sign-out aborted: local changes have not synced yet")
                return@withLock SignOutResult.PendingWrites
            }
            if (imageSyncService.hasPendingLocalImages()) {
                Log.w(TAG, "Sign-out aborted: some recipe images have not been uploaded yet")
                return@withLock SignOutResult.PendingWrites
            }
        }
        _authState.value = AuthState.Loading
        resetToFreshGuest()
        SignOutResult.Success
    }

    /**
     * Deletes all user data and the Firebase Auth account, then transitions
     * to guest mode.
     *
     * @param reauthCredential A fresh Google credential from [requestReauthCredential]
     *        (Firebase requires a recent login to delete an account). If null and the last
     *        sign-in is not recent, [ReauthenticationRequiredException] is thrown before
     *        anything is deleted.
     * @throws ReauthenticationRequiredException if re-authentication is needed
     * @throws AccountDeletionService.OfflineException if the server can't be reached
     * @throws AccountDeletionService.AuthAccountDeletionException if user data was deleted
     *         but the Firebase Auth account could not be. The app is still signed out.
     */
    suspend fun deleteAccountAndData(reauthCredential: AuthCredential? = null) {
        transitionMutex.withLock {
            val previousState = _authState.value
            _authState.value = AuthState.Loading
            try {
                accountDeletionService.deleteAllUserData(reauthCredential)
            } catch (e: AccountDeletionService.AuthAccountDeletionException) {
                ErrorLog.e(TAG, "User data deleted but auth account deletion failed", e)
                resetToFreshGuest()
                throw e
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Failed to delete account and data", e)
                _authState.value = previousState
                throw e
            }
            resetToFreshGuest()
        }
    }

    /**
     * Clears all local account state and signs out of Firebase, ending in a fresh
     * guest session. Caller must hold [transitionMutex] and have set [AuthState.Loading].
     */
    private suspend fun resetToFreshGuest() {
        clearLocalData(enableNetwork = false)
        Firebase.auth.signOut()
        clearCredentialState()
        imageSyncService.clearLocalCache()
        prefs.edit { remove(KEY_GUEST_UID) }
        _authState.value = AuthState.Guest(uid = getOrCreateGuestUid())
    }

    private suspend fun clearLocalData(enableNetwork: Boolean) {
        if (!firestoreService.clearLocalData(enableNetwork)) {
            firestoreService.reportFailure(DataFailure(DataOp.CLEAR_PREVIOUS_ACCOUNT))
        }
    }

    private suspend fun clearCredentialState() {
        try {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear credential state", e)
        }
    }

    private fun resolveCurrentState(): AuthState {
        val user = try {
            Firebase.auth.currentUser
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Could not read Firebase Auth user", e)
            null
        }
        return if (user != null && !user.isAnonymous) {
            AuthState.Google(uid = user.uid, email = user.email ?: "")
        } else {
            AuthState.Guest(uid = getOrCreateGuestUid())
        }
    }

    /** Returns the local guest UID, creating one if it doesn't exist yet. */
    private fun getOrCreateGuestUid(): String {
        val existing = prefs.getString(KEY_GUEST_UID, null)
        if (existing != null) return existing
        val newUid = "guest_${UUID.randomUUID()}"
        prefs.edit { putString(KEY_GUEST_UID, newUid) }
        return newUid
    }

    private fun webClientId(): String = context.getString(R.string.default_web_client_id)

    /** Silently (if possible) gets a fresh credential for the already-authorized account. */
    /**
     * Requests a fresh Google credential for the signed-in account, for re-authentication
     * before [deleteAccountAndData]. Call from a UI-owned scope; [activityContext] must not
     * outlive the Activity.
     */
    suspend fun requestReauthCredential(activityContext: Context): AuthCredential {
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setAutoSelectEnabled(true)
            .setServerClientId(webClientId())
            .build()
        return try {
            getGoogleCredential(activityContext, GetCredentialRequest.Builder().addCredentialOption(option).build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Re-authentication failed", e)
            throw ReauthenticationRequiredException(e)
        }
    }

    private suspend fun getGoogleCredential(activityContext: Context, request: GetCredentialRequest): AuthCredential {
        val result = try {
            CredentialManager.create(activityContext).getCredential(activityContext, request)
        } catch (e: NoCredentialException) {
            throw IllegalStateException("No matching Google credential is available", e)
        }
        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(result.credential.data)
        return GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
    }
}
