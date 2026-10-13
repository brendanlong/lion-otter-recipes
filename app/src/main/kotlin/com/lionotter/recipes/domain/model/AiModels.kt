package com.lionotter.recipes.domain.model

/** The Claude models the app can use, and the defaults for new installs. */
object AiModels {
    const val MODEL_OPUS = "claude-opus-5-5"
    const val MODEL_SONNET = "claude-sonnet-5-5"
    const val MODEL_HAIKU = "claude-haiku-5-5"
    const val MODEL_FABLE = "claude-fable-5-1"

    const val DEFAULT_MODEL = MODEL_SONNET
    const val DEFAULT_EDIT_MODEL = MODEL_HAIKU
    const val DEFAULT_THINKING_ENABLED = false

    val SUPPORTED_MODELS = listOf(MODEL_OPUS, MODEL_SONNET, MODEL_HAIKU, MODEL_FABLE)

    /**
     * Map a stored model ID (possibly from an older app version) to a model this app
     * supports. Retired or unknown IDs fall back to the current model of the same family,
     * or [default] if the family is unknown.
     */
    fun normalizeModel(model: String?, default: String = DEFAULT_MODEL): String {
        if (model.isNullOrBlank()) return default
        if (model in SUPPORTED_MODELS) return model
        return when {
            model.startsWith("claude-haiku") -> MODEL_HAIKU
            model.startsWith("claude-sonnet") -> MODEL_SONNET
            model.startsWith("claude-opus") -> MODEL_OPUS
            model.startsWith("claude-fable") -> MODEL_FABLE
            else -> default
        }
    }
}
