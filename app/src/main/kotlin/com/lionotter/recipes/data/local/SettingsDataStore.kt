package com.lionotter.recipes.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.lionotter.recipes.data.remote.AnthropicService
import com.lionotter.recipes.domain.model.StartOfWeek
import com.lionotter.recipes.domain.model.ThemeMode
import com.lionotter.recipes.domain.model.UnitSystem
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsDataStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private object Keys {
        val AI_MODEL = stringPreferencesKey("ai_model")
        val EXTENDED_THINKING_ENABLED = booleanPreferencesKey("extended_thinking_enabled")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val IMPORT_DEBUGGING_ENABLED = booleanPreferencesKey("import_debugging_enabled")
        val VOLUME_UNIT_SYSTEM = stringPreferencesKey("volume_unit_system")
        val WEIGHT_UNIT_SYSTEM = stringPreferencesKey("weight_unit_system")
        val GROCERY_VOLUME_UNIT_SYSTEM = stringPreferencesKey("grocery_volume_unit_system")
        val GROCERY_WEIGHT_UNIT_SYSTEM = stringPreferencesKey("grocery_weight_unit_system")
        val START_OF_WEEK = stringPreferencesKey("start_of_week")
    }

    private val tinkEncryption: TinkEncryption by lazy { TinkEncryption(context) }

    private val apiKeyPrefs by lazy {
        context.getSharedPreferences(API_KEY_PREFS_FILE, Context.MODE_PRIVATE)
    }

    private val _anthropicApiKey = MutableStateFlow<String?>(null)

    init {
        cleanUpOldEncryptedSharedPreferences()
        _anthropicApiKey.value = loadApiKey()
    }

    /**
     * Loads the API key from Tink-encrypted SharedPreferences.
     * Returns null if no key is stored or decryption fails.
     */
    private fun loadApiKey(): String? {
        return try {
            apiKeyPrefs.getString(API_KEY_PREF_KEY, null)?.let {
                tinkEncryption.decrypt(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load API key", e)
            null
        }
    }

    /**
     * Cleans up old EncryptedSharedPreferences files from the deprecated
     * androidx.security:security-crypto library. The API key stored there
     * cannot be read without the old library, so users will need to re-enter
     * their key after this migration.
     */
    private fun cleanUpOldEncryptedSharedPreferences() {
        try {
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            val filesToDelete = listOf(
                "secure_settings.xml",
                "__androidx_security_crypto_encrypted_prefs__.xml"
            )
            for (fileName in filesToDelete) {
                val file = File(prefsDir, fileName)
                if (file.exists()) {
                    if (file.delete()) {
                        Log.i(TAG, "Cleaned up old encrypted prefs file: $fileName")
                    } else {
                        Log.w(TAG, "Failed to delete old encrypted prefs file: $fileName")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up old EncryptedSharedPreferences files", e)
        }
    }

    val anthropicApiKey: Flow<String?> = _anthropicApiKey.asStateFlow()

    val aiModel: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[Keys.AI_MODEL] ?: AnthropicService.DEFAULT_MODEL
    }

    val extendedThinkingEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[Keys.EXTENDED_THINKING_ENABLED] ?: true
    }

    val keepScreenOn: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[Keys.KEEP_SCREEN_ON] ?: true
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { preferences ->
        val value = preferences[Keys.THEME_MODE]
        if (value != null) {
            try { ThemeMode.valueOf(value) } catch (_: IllegalArgumentException) { ThemeMode.AUTO }
        } else {
            ThemeMode.AUTO
        }
    }

    val volumeUnitSystem: Flow<UnitSystem> = context.dataStore.data.map { preferences ->
        val value = preferences[Keys.VOLUME_UNIT_SYSTEM]
        if (value != null) {
            try { UnitSystem.valueOf(value) } catch (_: IllegalArgumentException) { UnitSystem.localeDefault() }
        } else {
            UnitSystem.localeDefault()
        }
    }

    val weightUnitSystem: Flow<UnitSystem> = context.dataStore.data.map { preferences ->
        val value = preferences[Keys.WEIGHT_UNIT_SYSTEM]
        if (value != null) {
            try { UnitSystem.valueOf(value) } catch (_: IllegalArgumentException) { UnitSystem.localeDefault() }
        } else {
            UnitSystem.localeDefault()
        }
    }

    /**
     * Grocery list volume unit system. Falls back to the recipe volume unit system
     * when no grocery-specific preference has been set.
     */
    val groceryVolumeUnitSystem: Flow<UnitSystem> = context.dataStore.data.map { preferences ->
        val groceryValue = preferences[Keys.GROCERY_VOLUME_UNIT_SYSTEM]
        if (groceryValue != null) {
            try { UnitSystem.valueOf(groceryValue) } catch (_: IllegalArgumentException) { null }
        } else {
            null
        } ?: run {
            val recipeValue = preferences[Keys.VOLUME_UNIT_SYSTEM]
            if (recipeValue != null) {
                try { UnitSystem.valueOf(recipeValue) } catch (_: IllegalArgumentException) { UnitSystem.localeDefault() }
            } else {
                UnitSystem.localeDefault()
            }
        }
    }

    /**
     * Grocery list weight unit system. Falls back to the recipe weight unit system
     * when no grocery-specific preference has been set.
     */
    val groceryWeightUnitSystem: Flow<UnitSystem> = context.dataStore.data.map { preferences ->
        val groceryValue = preferences[Keys.GROCERY_WEIGHT_UNIT_SYSTEM]
        if (groceryValue != null) {
            try { UnitSystem.valueOf(groceryValue) } catch (_: IllegalArgumentException) { null }
        } else {
            null
        } ?: run {
            val recipeValue = preferences[Keys.WEIGHT_UNIT_SYSTEM]
            if (recipeValue != null) {
                try { UnitSystem.valueOf(recipeValue) } catch (_: IllegalArgumentException) { UnitSystem.localeDefault() }
            } else {
                UnitSystem.localeDefault()
            }
        }
    }

    val startOfWeek: Flow<StartOfWeek> = context.dataStore.data.map { preferences ->
        val value = preferences[Keys.START_OF_WEEK]
        if (value != null) {
            try { StartOfWeek.valueOf(value) } catch (_: IllegalArgumentException) { StartOfWeek.LOCALE_DEFAULT }
        } else {
            StartOfWeek.LOCALE_DEFAULT
        }
    }

    val importDebuggingEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[Keys.IMPORT_DEBUGGING_ENABLED] ?: false
    }

    suspend fun setAnthropicApiKey(apiKey: String) {
        withContext(Dispatchers.IO) {
            val encrypted = tinkEncryption.encrypt(apiKey)
            apiKeyPrefs.edit().putString(API_KEY_PREF_KEY, encrypted).commit()
            _anthropicApiKey.value = apiKey
        }
    }

    suspend fun clearAnthropicApiKey() {
        withContext(Dispatchers.IO) {
            apiKeyPrefs.edit().remove(API_KEY_PREF_KEY).commit()
            _anthropicApiKey.value = null
        }
    }

    suspend fun setAiModel(model: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.AI_MODEL] = model
        }
    }

    suspend fun setExtendedThinkingEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[Keys.EXTENDED_THINKING_ENABLED] = enabled
        }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[Keys.KEEP_SCREEN_ON] = enabled
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { preferences ->
            preferences[Keys.THEME_MODE] = mode.name
        }
    }

    suspend fun setVolumeUnitSystem(system: UnitSystem) {
        context.dataStore.edit { preferences ->
            preferences[Keys.VOLUME_UNIT_SYSTEM] = system.name
        }
    }

    suspend fun setWeightUnitSystem(system: UnitSystem) {
        context.dataStore.edit { preferences ->
            preferences[Keys.WEIGHT_UNIT_SYSTEM] = system.name
        }
    }

    suspend fun setGroceryVolumeUnitSystem(system: UnitSystem) {
        context.dataStore.edit { preferences ->
            preferences[Keys.GROCERY_VOLUME_UNIT_SYSTEM] = system.name
        }
    }

    suspend fun setGroceryWeightUnitSystem(system: UnitSystem) {
        context.dataStore.edit { preferences ->
            preferences[Keys.GROCERY_WEIGHT_UNIT_SYSTEM] = system.name
        }
    }

    suspend fun setStartOfWeek(startOfWeek: StartOfWeek) {
        context.dataStore.edit { preferences ->
            preferences[Keys.START_OF_WEEK] = startOfWeek.name
        }
    }

    suspend fun setImportDebuggingEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[Keys.IMPORT_DEBUGGING_ENABLED] = enabled
        }
    }

    companion object {
        private const val TAG = "SettingsDataStore"
        private const val API_KEY_PREFS_FILE = "tink_encrypted_api_key"
        private const val API_KEY_PREF_KEY = "encrypted_api_key"
    }
}
