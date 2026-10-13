package com.lionotter.recipes.domain.model

/** All of the user's settings at one point in time. */
data class Settings(
    val anthropicApiKey: String?,
    val aiModel: String,
    val editModel: String,
    val thinkingEnabled: Boolean,
    val keepScreenOn: Boolean,
    val volumeKeysScroll: Boolean,
    val themeMode: ThemeMode,
    val volumeUnitSystem: UnitSystem,
    val weightUnitSystem: UnitSystem,
    val groceryVolumeUnitSystem: UnitSystem,
    val groceryWeightUnitSystem: UnitSystem,
    val startOfWeek: StartOfWeek,
    val importDebuggingEnabled: Boolean
)
