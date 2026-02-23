package com.lionotter.recipes.data.remote

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.Firebase
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** Represents the current authentication state of the app. */
sealed class AuthState {
    /** App is initializing or transitioning between auth states. */
    data object Loading : AuthState()

    /** User is signed in anonymously (offline-only, no cloud sync). */
    data object Anonymous : AuthState()

    /** User is signed in with a Google account. */
    data class Google(val email: String) : AuthState()
}

/** Result of attempting to link an anonymous account with Google. */
sealed class LinkResult {
    /** Successfully linked — anonymous UID is preserved, no data migration needed. */
    data object Linked : LinkResult()

    /** Google account already exists — need to migrate data from anonymous to Google. */
    data class NeedsMerge(val anonymousUid: String, val credential: AuthCredential) : LinkResult()
}

@Singleton
class AuthService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firestoreService: FirestoreService
) {
    companion object {
        private const val TAG = "AuthService"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isSignedIn = MutableStateFlow(Firebase.auth.currentUser != null)
    val isSignedIn: StateFlow<Boolean> = _isSignedIn.asStateFlow()

    private val _currentUserEmail = MutableStateFlow(Firebase.auth.currentUser?.email)
    val currentUserEmail: StateFlow<String?> = _currentUserEmail.asStateFlow()

    private val _currentUserId = MutableStateFlow(Firebase.auth.currentUser?.uid)
    /** Emits the current Firebase UID (or null). Repositories observe this to reset their listeners. */
    val currentUserId: StateFlow<String?> = _currentUserId.asStateFlow()

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    init {
        // Set initial auth state based on current user
        updateAuthState(Firebase.auth.currentUser)

        Firebase.auth.addAuthStateListener { auth ->
            _isSignedIn.value = auth.currentUser != null
            _currentUserEmail.value = auth.currentUser?.email
            _currentUserId.value = auth.currentUser?.uid
            // Only update authState from listener when not in Loading (transition) state.
            // During transitions (sign-out -> anonymous), we control authState manually.
            if (_authState.value !is AuthState.Loading) {
                if (auth.currentUser == null) {
                    // User signed out unexpectedly (e.g., token revoked).
                    // Re-establish an anonymous session to avoid a frozen UI.
                    serviceScope.launch {
                        ensureSignedIn()
                    }
                } else {
                    updateAuthState(auth.currentUser)
                }
            }
        }
    }

    private fun updateAuthState(user: com.google.firebase.auth.FirebaseUser?) {
        _authState.value = when {
            user == null -> AuthState.Loading
            user.isAnonymous -> AuthState.Anonymous
            else -> AuthState.Google(user.email ?: "")
        }
    }

    /**
     * Ensures the user is signed in (either Google or anonymously).
     * Called during app startup. If no user exists, signs in anonymously
     * with Firestore network disabled.
     */
    suspend fun ensureSignedIn() {
        val currentUser = Firebase.auth.currentUser
        if (currentUser != null) {
            if (currentUser.isAnonymous) {
                // Anonymous user from previous session — keep network disabled
                firestoreService.disableNetwork()
            }
            updateAuthState(currentUser)
            return
        }
        // No user — sign in anonymously with network off
        _authState.value = AuthState.Loading
        firestoreService.disableNetwork()
        val result = signInAnonymously()
        if (result.isFailure) {
            // Stay in Loading state — caller should show error
            Log.e(TAG, "Failed to sign in anonymously during startup")
        }
    }

    /** Sign in anonymously. Returns Result for error handling. */
    suspend fun signInAnonymously(): Result<Unit> {
        return try {
            Firebase.auth.signInAnonymously().await()
            _authState.value = AuthState.Anonymous
            _isSignedIn.value = true
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Anonymous sign-in failed", e)
            Result.failure(e)
        }
    }

    suspend fun signInWithGoogle(activityContext: Context): Result<Unit> {
        return try {
            val credential = getGoogleCredential(activityContext)
            Firebase.auth.signInWithCredential(credential).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Google sign-in failed", e)
            Result.failure(e)
        }
    }

    /**
     * Links the current anonymous account with a Google credential.
     * If the Google account already exists, returns [LinkResult.NeedsMerge].
     */
    suspend fun linkWithGoogle(activityContext: Context): Result<LinkResult> {
        val anonymousUser = Firebase.auth.currentUser
            ?: return Result.failure(IllegalStateException("No current user"))
        val anonymousUid = anonymousUser.uid

        return try {
            val credential = getGoogleCredential(activityContext)
            try {
                // Try linking — preserves UID, no data migration needed
                anonymousUser.linkWithCredential(credential).await()
                firestoreService.enableNetwork()
                _authState.value = AuthState.Google(Firebase.auth.currentUser?.email ?: "")
                Result.success(LinkResult.Linked)
            } catch (e: FirebaseAuthUserCollisionException) {
                // Google account already exists — need to migrate data
                Result.success(LinkResult.NeedsMerge(anonymousUid, credential))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to link Google account", e)
            Result.failure(e)
        }
    }

    /**
     * Sets auth state to [AuthState.Loading], suppressing the auth state
     * listener during the merge transition so that intermediate states
     * (Firestore terminate/re-init) don't trigger premature UI updates.
     */
    fun beginMergeTransition() {
        _authState.value = AuthState.Loading
    }

    /**
     * Ends the merge transition by re-reading the current Firebase user.
     * Used when migration fails and we need to restore the auth state
     * (typically back to [AuthState.Anonymous]).
     */
    fun endMergeTransition() {
        updateAuthState(Firebase.auth.currentUser)
    }

    /**
     * Completes the merge flow after [AccountMigrationService] has finished
     * migrating data and signing in the Google user on the default app.
     *
     * Repositories automatically re-subscribe because [FirestoreService.clearLocalData]
     * increments a generation counter that they observe alongside [currentUserId].
     */
    fun completeMergeSignIn() {
        updateAuthState(Firebase.auth.currentUser)
    }

    /**
     * Signs out the current user and transitions to anonymous mode.
     * Clears Firestore persistence and re-signs-in anonymously.
     */
    suspend fun signOut() {
        _authState.value = AuthState.Loading
        firestoreService.clearLocalData()
        Firebase.auth.signOut()
        // Now sign in anonymously with network disabled
        firestoreService.disableNetwork()
        signInAnonymously()
        // AuthState is updated inside signInAnonymously()
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
