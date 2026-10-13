package com.lionotter.recipes.domain.port

import com.lionotter.recipes.domain.model.StartOfWeek
import com.lionotter.recipes.domain.model.ThemeMode
import com.lionotter.recipes.domain.model.UnitSystem
import kotlinx.coroutines.flow.Flow

/** All of the user's settings, readable as flows and writable (writes throw on failure). */
interface AppSettings : AiSettings {
    /** The model for edits and regeneration. */
    val editModel: Flow<String>
    val keepScreenOn: Flow<Boolean>
    val themeMode: Flow<ThemeMode>
    val volumeUnitSystem: Flow<UnitSystem>
    val weightUnitSystem: Flow<UnitSystem>

    /** Falls back to [volumeUnitSystem] until set. */
    val groceryVolumeUnitSystem: Flow<UnitSystem>

    /** Falls back to [weightUnitSystem] until set. */
    val groceryWeightUnitSystem: Flow<UnitSystem>
    val startOfWeek: Flow<StartOfWeek>

    suspend fun setAnthropicApiKey(apiKey: String)
    suspend fun clearAnthropicApiKey()
    suspend fun setAiModel(model: String)
    suspend fun setEditModel(model: String)
    suspend fun setThinkingEnabled(enabled: Boolean)
    suspend fun setKeepScreenOn(enabled: Boolean)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setVolumeUnitSystem(system: UnitSystem)
    suspend fun setWeightUnitSystem(system: UnitSystem)
    suspend fun setGroceryVolumeUnitSystem(system: UnitSystem)
    suspend fun setGroceryWeightUnitSystem(system: UnitSystem)
    suspend fun setStartOfWeek(startOfWeek: StartOfWeek)
    suspend fun setImportDebuggingEnabled(enabled: Boolean)
}
