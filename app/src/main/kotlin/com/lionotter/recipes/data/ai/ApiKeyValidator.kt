package com.lionotter.recipes.data.ai

/** Why an API key can't be used. */
enum class ApiKeyProblem { EMPTY, BAD_PREFIX, ANTHROPIC_KEY }

object ApiKeyValidator {
    const val ANTHROPIC_API_KEY_PREFIX = "sk-ant-"
    const val OPENAI_API_KEY_PREFIX = "sk-"

    /** Checks the format of an API key that should start with [prefix]. Returns null if it looks valid. */
    fun validate(apiKey: String, prefix: String): ApiKeyProblem? {
        val trimmed = apiKey.trim()
        return when {
            trimmed.isBlank() -> ApiKeyProblem.EMPTY
            !trimmed.startsWith(prefix) -> ApiKeyProblem.BAD_PREFIX
            // OpenAI's prefix is a prefix of Anthropic's
            prefix != ANTHROPIC_API_KEY_PREFIX && trimmed.startsWith(ANTHROPIC_API_KEY_PREFIX) -> ApiKeyProblem.ANTHROPIC_KEY
            else -> null
        }
    }
}
