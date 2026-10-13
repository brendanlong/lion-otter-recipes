package com.lionotter.recipes.domain.port

import com.lionotter.recipes.domain.model.Settings
import com.lionotter.recipes.domain.model.StartOfWeek
import com.lionotter.recipes.domain.model.ThemeMode
import com.lionotter.recipes.domain.model.UnitSystem
import kotlinx.coroutines.flow.Flow

/** All of the user's settings, readable as flows and writable (writes throw on failure). */
interface AppSettings : AiSettings, TagPreferences {
    /** Everything at once; emits once the settings (including the API key) have been read. */
    val settings: Flow<Settings>

    val keepScreenOn: Flow<Boolean>

    /** Whether the volume buttons scroll long screens by a page. */
    val volumeKeysScroll: Flow<Boolean>
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
    suspend fun setOpenAiApiKey(apiKey: String)
    suspend fun clearOpenAiApiKey()
    suspend fun setImageGenerationEnabled(enabled: Boolean)
    suspend fun setAiModel(model: String)
    suspend fun setEditModel(model: String)
    suspend fun setThinkingEnabled(enabled: Boolean)
    suspend fun setKeepScreenOn(enabled: Boolean)
    suspend fun setVolumeKeysScroll(enabled: Boolean)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setVolumeUnitSystem(system: UnitSystem)
    suspend fun setWeightUnitSystem(system: UnitSystem)
    suspend fun setGroceryVolumeUnitSystem(system: UnitSystem)
    suspend fun setGroceryWeightUnitSystem(system: UnitSystem)
    suspend fun setStartOfWeek(startOfWeek: StartOfWeek)
    suspend fun setImportDebuggingEnabled(enabled: Boolean)
}
