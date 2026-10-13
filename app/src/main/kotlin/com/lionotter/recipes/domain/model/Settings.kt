package com.lionotter.recipes.domain.model

/** All of the user's settings at one point in time. */
data class Settings(
    /** Provider ID to API key, in the order the user added them. */
    val apiKeys: Map<String, String>,
    /** The models in use for each role (see [AiProviders.resolve]); a role is missing if no configured provider has a model for it. */
    val models: Map<ModelRole, AiModel>,
    val thinkingEnabled: Boolean,
    val keepScreenOn: Boolean,
    val volumeKeysScroll: Boolean,
    val themeMode: ThemeMode,
    val volumeUnitSystem: UnitSystem,
    val weightUnitSystem: UnitSystem,
    val groceryVolumeUnitSystem: UnitSystem,
    val groceryWeightUnitSystem: UnitSystem,
    val startOfWeek: StartOfWeek,
    val importDebuggingEnabled: Boolean,
    val imageGenerationEnabled: Boolean,
    val tagBarSize: Int
)
