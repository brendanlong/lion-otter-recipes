package com.lionotter.recipes.data.sync

import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Auth state for the UI layer.
 */
data class AuthState(
    val isSignedIn: Boolean = false,
    val userId: String? = null,
    val displayName: String? = null,
    val email: String? = null,
    val avatarUrl: String? = null
)

/**
 * Manages authentication state via Supabase Auth.
 * Observes the Supabase session status and exposes a reactive [AuthState].
 */
@Singleton
class AuthRepository @Inject constructor(
    private val supabaseClient: SupabaseClient,
    @com.lionotter.recipes.di.ApplicationScope private val applicationScope: CoroutineScope
) {
    companion object {
        private const val TAG = "AuthRepository"
    }

    private val _authState = MutableStateFlow(AuthState())
    val authState: StateFlow<AuthState> = _authState

    val isSignedIn: StateFlow<Boolean> = _authState
        .map { it.isSignedIn }
        .stateIn(applicationScope, SharingStarted.Eagerly, false)

    init {
        applicationScope.launch {
            supabaseClient.auth.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        val user = status.session.user
                        _authState.value = AuthState(
                            isSignedIn = true,
                            userId = user?.id,
                            displayName = (user?.userMetadata?.get("full_name") as? JsonPrimitive)?.content,
                            email = user?.email,
                            avatarUrl = (user?.userMetadata?.get("avatar_url") as? JsonPrimitive)?.content
                        )
                        Log.d(TAG, "Authenticated as ${user?.email}")
                    }
                    is SessionStatus.NotAuthenticated -> {
                        _authState.value = AuthState()
                        Log.d(TAG, "Not authenticated")
                    }
                    is SessionStatus.Initializing -> {
                        Log.d(TAG, "Auth initializing")
                    }
                    is SessionStatus.RefreshFailure -> {
                        Log.w(TAG, "Session refresh failed: ${status.cause}")
                    }
                }
            }
        }
    }

    suspend fun signOut() {
        try {
            supabaseClient.auth.signOut()
        } catch (e: Exception) {
            Log.e(TAG, "Sign out failed", e)
            throw e
        }
    }
}
