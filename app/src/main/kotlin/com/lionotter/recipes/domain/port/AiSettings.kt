package com.lionotter.recipes.domain.port

import com.lionotter.recipes.domain.model.AiProviders
import com.lionotter.recipes.domain.model.ConfiguredModel
import com.lionotter.recipes.domain.model.ModelRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** The user's AI settings, as the import, edit, regenerate, tag cleanup and image use cases need them. */
interface AiSettings {
    /** Provider ID to API key for every provider with a key, in the order the user added them. */
    val apiKeys: Flow<Map<String, String>>

    /**
     * The model the user picked for [role], or null for the default. May be a retired ID or a
     * model whose provider has no key any more; use [model] for the model to actually use.
     */
    fun selectedModel(role: ModelRole): Flow<String?>

    val thinkingEnabled: Flow<Boolean>

    /** Whether to keep each import's input and AI output for the import debugging screen. */
    val importDebuggingEnabled: Flow<Boolean>

    /** Whether recipes imported without an image get a generated one (only with an image model). */
    val imageGenerationEnabled: Flow<Boolean>
}

/** The model to use for [role] with its API key (see [AiProviders.resolve]); null if no provider with such a model has a key. */
fun AiSettings.model(role: ModelRole): Flow<ConfiguredModel?> =
    combine(selectedModel(role), apiKeys) { selected, keys -> AiProviders.resolve(role, selected, keys) }
        .distinctUntilChanged()
