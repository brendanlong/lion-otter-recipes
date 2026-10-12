package com.lionotter.recipes.data.remote

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.Firebase
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import com.lionotter.recipes.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Represents the current authentication state of the app. */
sealed class AuthState {
    /** Transitioning between auth states (sign-out, account deletion). */
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
    @ApplicationContext private val context: Context,
    private val firestoreService: FirestoreService,
    private val accountDeletionService: AccountDeletionService
) {
    companion object {
        private const val TAG = "AuthService"
        private const val PREFS_NAME = "auth_prefs"
        private const val KEY_GUEST_UID = "guest_uid"
        private const val AUTH_RESOLUTION_TIMEOUT_MS = 30_000L
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

    /** Returns the current guest UID, or null if none exists. */
    fun getGuestUid(): String? = prefs.getString(KEY_GUEST_UID, null)

    /**
     * Returns the current UID, waiting for an in-progress auth transition to finish.
     * @throws IllegalStateException if no UID becomes available within the timeout.
     */
    suspend fun awaitUid(): String {
        val state = withTimeoutOrNull(AUTH_RESOLUTION_TIMEOUT_MS) {
            _authState.first { it !is AuthState.Loading }
        }
        return state?.uid ?: throw IllegalStateException("User not authenticated")
    }

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
            Log.e(TAG, "Google sign-in failed", e)
            Result.failure(e)
        }
    }

    /**
     * Completes the merge flow after [AccountMigrationService] has finished
     * migrating data and signing in the Google user on the default app.
     *
     * The ordering here is critical to avoid PERMISSION_DENIED crashes:
     * 1. Update auth state to Google (repos see the Google UID)
     * 2. Clear guest Firestore cache (repos recreate listeners on the Google path)
     * 3. Enable network (repos are already listening on the Google path)
     */
    suspend fun completeMergeSignIn() {
        transitionMutex.withLock {
            val state = resolveCurrentState()
            _authState.value = state
            firestoreService.clearLocalData(enableNetwork = state is AuthState.Google)
        }
    }

    /**
     * Restores the auth state after a failed merge transition.
     */
    fun restoreAfterFailedMerge() {
        _authState.value = resolveCurrentState()
    }

    /**
     * Signs out the current Google user and transitions to guest mode.
     * Clears Firestore persistence and generates a new guest UID.
     *
     * Unless [force] is set, first waits for pending writes to reach the server and
     * returns [SignOutResult.PendingWrites] (without signing out) if they don't, so
     * the UI can warn that unsynced changes would be lost.
     */
    suspend fun signOut(force: Boolean = false): SignOutResult = transitionMutex.withLock {
        if (!force && _authState.value is AuthState.Google &&
            !firestoreService.awaitPendingWrites(PENDING_WRITES_TIMEOUT_MS)
        ) {
            Log.w(TAG, "Sign-out aborted: local changes have not synced yet")
            return@withLock SignOutResult.PendingWrites
        }
        _authState.value = AuthState.Loading
        resetToFreshGuest()
        SignOutResult.Success
    }

    /**
     * Deletes all user data and the Firebase Auth account, then transitions
     * to guest mode.
     *
     * @param activityContext An Activity context used to request a fresh Google credential
     *        for re-authentication (Firebase requires a recent login to delete an account).
     *        If null and the last sign-in is not recent, [ReauthenticationRequiredException]
     *        is thrown before anything is deleted.
     * @throws ReauthenticationRequiredException if re-authentication is needed
     * @throws AccountDeletionService.OfflineException if the server can't be reached
     * @throws AccountDeletionService.AuthAccountDeletionException if user data was deleted
     *         but the Firebase Auth account could not be. The app is still signed out.
     */
    suspend fun deleteAccountAndData(activityContext: Context? = null) {
        transitionMutex.withLock {
            val credential = activityContext?.let { getReauthCredential(it) }
            val previousState = _authState.value
            _authState.value = AuthState.Loading
            try {
                accountDeletionService.deleteAllUserData(credential)
            } catch (e: AccountDeletionService.AuthAccountDeletionException) {
                Log.e(TAG, "User data deleted but auth account deletion failed", e)
                resetToFreshGuest()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete account and data", e)
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
        firestoreService.clearLocalData(enableNetwork = false)
        Firebase.auth.signOut()
        clearCredentialState()
        ImageCacheConfig.clearCache(context)
        prefs.edit { remove(KEY_GUEST_UID) }
        _authState.value = AuthState.Guest(uid = getOrCreateGuestUid())
    }

    private suspend fun clearCredentialState() {
        try {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Failed to clear credential state", e)
        }
    }

    private fun resolveCurrentState(): AuthState {
        val user = try {
            Firebase.auth.currentUser
        } catch (e: Exception) {
            Log.e(TAG, "Could not read Firebase Auth user", e)
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
    private suspend fun getReauthCredential(activityContext: Context): AuthCredential {
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
        val result = CredentialManager.create(activityContext).getCredential(activityContext, request)
        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(result.credential.data)
        return GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
    }
}
