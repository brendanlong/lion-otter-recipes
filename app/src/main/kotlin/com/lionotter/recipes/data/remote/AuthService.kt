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
import com.lionotter.recipes.util.ErrorLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Qualifier
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

/**
 * Thrown by [com.lionotter.recipes.data.account.AccountLifecycle.deleteAccountAndData] when the
 * Google account must be re-authenticated before deletion. Nothing has been deleted. Retry with an
 * Activity context so a fresh Google credential can be requested.
 */
class ReauthenticationRequiredException(cause: Throwable? = null) :
    Exception("Please sign in again to delete your account", cause)

/** What happens to the signed-in account during an auth transition. */
enum class AccountTransition {
    /** A guest signs in with Google: their data becomes the Google account's. */
    MERGE,

    /** Sign-out or account deletion: the account and its data leave the device. */
    LEAVE,
}

/**
 * Thrown by [AuthService.awaitUid] during an [AccountTransition.LEAVE]: the account the caller
 * was working for is leaving the device, and its work must not continue in the next account.
 */
class AccountLeftException : IllegalStateException("The account is being signed out")

/** The Web OAuth client ID Google sign-in requests ID tokens for. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WebClientId

/**
 * The auth state as [com.lionotter.recipes.data.account.AccountLifecycle] drives it during
 * sign-in, sign-out and account deletion (implemented by [AuthService]).
 */
interface AuthSession {
    val authState: StateFlow<AuthState>

    /** Publishes [AuthState.Loading] until [endTransition]. */
    fun beginTransition(kind: AccountTransition)

    /** The state Firebase Auth is in now (creating a guest UID if needed), without publishing it. */
    fun resolveState(): AuthState

    /** Ends a transition by publishing [state]. */
    fun endTransition(state: AuthState)

    /**
     * Signs out of Firebase Auth and Credential Manager and forgets the guest UID, so the next
     * [resolveState] is a fresh guest.
     */
    suspend fun signOutAndForgetGuest()
}

/**
 * Firebase Auth and Google credentials: owns the app's authentication state and the local guest
 * UID. Account transitions (sign-in migration, sign-out, deletion) and the local data they clear
 * are orchestrated by [com.lionotter.recipes.data.account.AccountLifecycle].
 *
 * The state is resolved synchronously on construction (from the persisted
 * Firebase Auth user), so it is never [AuthState.Loading] at startup — even in a
 * process started only to run a WorkManager job.
 */
@Singleton
class AuthService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:WebClientId private val webClientId: String
) : AuthSession {
    companion object {
        private const val TAG = "AuthService"
        private const val PREFS_NAME = "auth_prefs"
        private const val KEY_GUEST_UID = "guest_uid"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _authState = MutableStateFlow(resolveState())
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    /**
     * Counts the edges of [AccountTransition.LEAVE]s: odd while one is in progress (it is bumped
     * before [AuthState.Loading] is published and after the resulting state is), so one read tells
     * whether an account has left since. See [awaitUid].
     */
    @Volatile
    var leaveEpoch = 0
        private set

    /** Whether the current user is signed in with Google (not a guest). */
    fun isGoogleUser(): Boolean = _authState.value is AuthState.Google

    /**
     * Returns the current UID. During a guest's sign-in ([AccountTransition.MERGE]) this waits
     * for the transition (which always ends, successfully or not) and returns the resulting
     * account's UID. If an account leaves the device (sign-out, deletion) after [epoch] (by
     * default: when this is called), it throws [AccountLeftException] instead, so nothing started
     * for the outgoing account reads or writes the next one.
     *
     * @param epoch the [leaveEpoch] read when the caller's work for the account started
     */
    suspend fun awaitUid(epoch: Int = leaveEpoch): String {
        if (epoch % 2 != 0) throw AccountLeftException()
        val state = _authState.first { it !is AuthState.Loading || leaveEpoch != epoch }
        if (leaveEpoch != epoch) throw AccountLeftException()
        return checkNotNull(state.uid)
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
            val option = GetSignInWithGoogleOption.Builder(webClientId).build()
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
     * Requests a fresh Google credential for the signed-in account, for re-authentication
     * before account deletion. Call from a UI-owned scope; [activityContext] must not
     * outlive the Activity.
     */
    suspend fun requestReauthCredential(activityContext: Context): AuthCredential {
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setAutoSelectEnabled(true)
            .setServerClientId(webClientId)
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

    override fun beginTransition(kind: AccountTransition) {
        if (kind == AccountTransition.LEAVE) leaveEpoch++
        _authState.value = AuthState.Loading
    }

    override fun endTransition(state: AuthState) {
        _authState.value = state
        if (leaveEpoch % 2 != 0) leaveEpoch++
    }

    override fun resolveState(): AuthState {
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

    override suspend fun signOutAndForgetGuest() {
        Firebase.auth.signOut()
        clearCredentialState()
        prefs.edit { remove(KEY_GUEST_UID) }
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

    /** Returns the local guest UID, creating one if it doesn't exist yet. */
    private fun getOrCreateGuestUid(): String {
        val existing = prefs.getString(KEY_GUEST_UID, null)
        if (existing != null) return existing
        val newUid = "guest_${UUID.randomUUID()}"
        prefs.edit { putString(KEY_GUEST_UID, newUid) }
        return newUid
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
