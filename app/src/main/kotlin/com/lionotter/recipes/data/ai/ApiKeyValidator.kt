package com.lionotter.recipes.data.ai

/** Why an API key can't be used. */
enum class ApiKeyProblem { EMPTY, BAD_PREFIX }

object ApiKeyValidator {
    const val API_KEY_PREFIX = "sk-ant-"

    /** Checks an Anthropic API key's format. Returns null if it looks valid. */
    fun validate(apiKey: String): ApiKeyProblem? {
        val trimmed = apiKey.trim()
        return when {
            trimmed.isBlank() -> ApiKeyProblem.EMPTY
            !trimmed.startsWith(API_KEY_PREFIX) -> ApiKeyProblem.BAD_PREFIX
            else -> null
        }
    }
}
