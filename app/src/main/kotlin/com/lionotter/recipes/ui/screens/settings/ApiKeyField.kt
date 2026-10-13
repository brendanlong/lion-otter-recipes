package com.lionotter.recipes.ui.screens.settings

import com.lionotter.recipes.R
import com.lionotter.recipes.data.ai.ApiKeyProblem
import com.lionotter.recipes.data.ai.ApiKeyValidator
import com.lionotter.recipes.messages.UserMessage
import com.lionotter.recipes.util.ErrorLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The settings field for entering an API key that starts with [prefix], stored with [store]. */
class ApiKeyField(
    private val scope: CoroutineScope,
    private val prefix: String,
    private val store: suspend (String) -> Unit
) {
    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    /** Why the entered key couldn't be saved, shown under the field until it is edited. */
    private val _error = MutableStateFlow<UserMessage?>(null)
    val error: StateFlow<UserMessage?> = _error.asStateFlow()

    fun onInputChange(input: String) {
        _input.value = input
        _error.value = null
    }

    fun save() {
        val key = _input.value.trim()
        when (ApiKeyValidator.validate(key, prefix)) {
            ApiKeyProblem.EMPTY -> _error.value = UserMessage(R.string.error_api_key_empty)
            ApiKeyProblem.BAD_PREFIX -> _error.value = UserMessage(R.string.error_api_key_prefix, prefix)
            ApiKeyProblem.ANTHROPIC_KEY -> _error.value = UserMessage(R.string.error_api_key_is_anthropic)
            null -> scope.launch {
                try {
                    withContext(NonCancellable) { store(key) }
                    _input.value = ""
                    _error.value = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ErrorLog.e(TAG, "Failed to save API key", e)
                    _error.value = UserMessage(R.string.error_api_key_save)
                }
            }
        }
    }

    private companion object {
        const val TAG = "ApiKeyField"
    }
}
