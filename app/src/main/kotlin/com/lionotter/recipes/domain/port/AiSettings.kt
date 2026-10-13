package com.lionotter.recipes.domain.port

import kotlinx.coroutines.flow.Flow

/** The user's AI settings, as the import, edit and regenerate use cases need them. */
interface AiSettings {
    /** The stored Anthropic API key, or null if there is none. */
    val anthropicApiKey: Flow<String?>

    /** The model for imports (may be a retired ID; see [AiModels.normalizeModel][com.lionotter.recipes.domain.model.AiModels.normalizeModel]). */
    val aiModel: Flow<String>

    val thinkingEnabled: Flow<Boolean>

    /** Whether to keep each import's input and AI output for the import debugging screen. */
    val importDebuggingEnabled: Flow<Boolean>
}
