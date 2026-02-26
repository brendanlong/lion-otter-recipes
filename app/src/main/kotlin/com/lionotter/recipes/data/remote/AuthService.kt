package com.lionotter.recipes.data.remote

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.sentry.Sentry
import androidx.core.content.edit
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.Firebase
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Represents the current authentication state of the app. */
sealed class AuthState {
    /** App is initializing or transitioning between auth states. */
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
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    /** Whether the current user is signed in with Google (not a guest). */
    fun isGoogleUser(): Boolean = _authState.value is AuthState.Google

    /** Returns the current guest UID, or null if none exists. */
    fun getGuestUid(): String? = prefs.getString(KEY_GUEST_UID, null)

    /**
     * Ensures the user has a valid session on startup.
     * For Google users, enables network. For guests, disables network and
     * uses a local UUID (no Firebase Auth call).
     */
    suspend fun ensureSignedIn() {
        val firebaseUser = Firebase.auth.currentUser
        if (firebaseUser != null && !firebaseUser.isAnonymous) {
            _authState.value = AuthState.Google(
                uid = firebaseUser.uid,
                email = firebaseUser.email ?: ""
            )
            return
        }

        // Guest mode — no Firebase Auth needed
        firestoreService.disableNetwork()
        _authState.value = AuthState.Guest(uid = getOrCreateGuestUid())
    }

    /**
     * Signs in with Google. Always uses the migration flow to copy
     * guest data to the Google account since the guest UID is local-only
     * and never existed in Firebase.
     *
     * @return The Google [AuthCredential] for use by [AccountMigrationService]
     */
    suspend fun signInWithGoogle(activityContext: Context): Result<AuthCredential> {
        return try {
            val credential = getGoogleCredential(activityContext)
            Result.success(credential)
        } catch (e: Exception) {
            Log.e(TAG, "Google sign-in failed", e)
            Sentry.captureException(e)
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
        val user = Firebase.auth.currentUser
        _authState.value = if (user != null) {
            AuthState.Google(uid = user.uid, email = user.email ?: "")
        } else {
            AuthState.Guest(uid = getOrCreateGuestUid())
        }
        firestoreService.clearLocalData()
        firestoreService.enableNetwork()
    }

    /**
     * Restores the auth state after a failed merge transition.
     */
    fun restoreAfterFailedMerge() {
        val user = Firebase.auth.currentUser
        _authState.value = if (user != null && !user.isAnonymous) {
            AuthState.Google(uid = user.uid, email = user.email ?: "")
        } else {
            AuthState.Guest(uid = getOrCreateGuestUid())
        }
    }

    /**
     * Signs out the current Google user and transitions to guest mode.
     * Clears Firestore persistence and generates a new guest UID.
     */
    suspend fun signOut() {
        _authState.value = AuthState.Loading
        firestoreService.clearLocalData()
        Firebase.auth.signOut()
        // Generate a fresh guest UID for the new session
        prefs.edit { remove(KEY_GUEST_UID) }
        firestoreService.disableNetwork()
        _authState.value = AuthState.Guest(uid = getOrCreateGuestUid())
    }

    /**
     * Deletes all user data and the Firebase Auth account, then transitions
     * to guest mode. Data deletion is performed by [AccountDeletionService]
     * before the auth account is removed.
     *
     * If deletion fails, the auth state is restored so the UI is not stuck
     * on [AuthState.Loading].
     */
    suspend fun deleteAccountAndData() {
        _authState.value = AuthState.Loading
        try {
            accountDeletionService.deleteAllUserData()
            firestoreService.clearLocalData()
            // Generate a fresh guest UID
            prefs.edit { remove(KEY_GUEST_UID) }
            firestoreService.disableNetwork()
            _authState.value = AuthState.Guest(uid = getOrCreateGuestUid())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete account and data", e)
            Sentry.captureException(e)
            restoreAfterFailedMerge()
            throw e
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

    /** Extracts a Google [AuthCredential] using Credential Manager. */
    private suspend fun getGoogleCredential(activityContext: Context): AuthCredential {
        val credentialManager = CredentialManager.create(activityContext)
        val webClientId = activityContext.getString(
            activityContext.resources.getIdentifier(
                "default_web_client_id", "string", activityContext.packageName
            )
        )

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webClientId)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val result = credentialManager.getCredential(activityContext, request)
        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(result.credential.data)
        return GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
    }
}
