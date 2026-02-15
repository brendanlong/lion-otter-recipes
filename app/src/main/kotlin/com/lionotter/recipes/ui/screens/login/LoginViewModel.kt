package com.lionotter.recipes.ui.screens.login

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lionotter.recipes.data.remote.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authService: AuthService
) : ViewModel() {

    companion object {
        private const val TAG = "LoginViewModel"
    }

    sealed class LoginState {
        object Idle : LoginState()
        object Loading : LoginState()
        data class Error(val message: String) : LoginState()
    }

    private val _loginState = MutableStateFlow<LoginState>(LoginState.Idle)
    val loginState: StateFlow<LoginState> = _loginState.asStateFlow()

    fun signIn(activityContext: Context) {
        if (_loginState.value is LoginState.Loading) return

        _loginState.value = LoginState.Loading
        viewModelScope.launch {
            val result = authService.signInWithGoogle(activityContext)
            if (result.isFailure) {
                val message = result.exceptionOrNull()?.message ?: "Sign-in failed"
                Log.e(TAG, "Sign-in failed: $message")
                _loginState.value = LoginState.Error(message)
            }
            // On success, AuthService.isSignedIn StateFlow updates automatically
            // and MainActivity recomposes to show NavGraph
        }
    }

    fun resetError() {
        _loginState.value = LoginState.Idle
    }
}
