package com.lionotter.recipes.domain.port

import kotlinx.coroutines.flow.Flow

/** The user's AI settings, as the import, edit, regenerate and tag cleanup use cases need them. */
interface AiSettings {
    /** The stored Anthropic API key, or null if there is none. */
    val anthropicApiKey: Flow<String?>

    /** The model for imports (may be a retired ID; see [AiModels.normalizeModel][com.lionotter.recipes.domain.model.AiModels.normalizeModel]). */
    val aiModel: Flow<String>

    /** The model for edits, regeneration and tag cleanup. */
    val editModel: Flow<String>

    val thinkingEnabled: Flow<Boolean>

    /** Whether to keep each import's input and AI output for the import debugging screen. */
    val importDebuggingEnabled: Flow<Boolean>

    /** The stored OpenAI API key, used only to generate recipe images; null if there is none. */
    val openAiApiKey: Flow<String?>

    /** Whether recipes imported without an image get a generated one (only with an [openAiApiKey]). */
    val imageGenerationEnabled: Flow<Boolean>
}
