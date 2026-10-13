package com.lionotter.recipes.data.local

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lionotter.recipes.domain.port.AppSettings
import com.lionotter.recipes.util.ErrorLog
import com.lionotter.recipes.util.IoDispatcher
import com.lionotter.recipes.util.enumOrNull
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.lionotter.recipes.di.ApplicationScope
import com.lionotter.recipes.domain.model.AiModels
import com.lionotter.recipes.domain.model.Settings
import com.lionotter.recipes.domain.model.StartOfWeek
import com.lionotter.recipes.domain.model.TagBarSize
import com.lionotter.recipes.domain.model.ThemeMode
import com.lionotter.recipes.domain.model.UnitSystem
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * The app settings. They are device preferences, kept across sign-out, except that account
 * deletion also removes the API key (the user's credential) and the favorite tags (the user's
 * content); see [clearUserData].
 */
@Singleton
class SettingsDataStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @ApplicationScope appScope: CoroutineScope,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : AppSettings, PerUserLocalData {
    override val scope = LocalDataScope.DEVICE

    /** Removes the API key and the favorite tags; the other settings are kept. */
    override suspend fun clearUserData() {
        val apiKeyFailure = try {
            clearAnthropicApiKey()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }
        try {
            context.dataStore.edit { it.remove(Keys.FAVORITE_TAGS) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            apiKeyFailure?.let(e::addSuppressed)
            throw e
        }
        apiKeyFailure?.let { throw it }
    }

    private object Keys {
        val AI_MODEL = stringPreferencesKey("ai_model")
        val EDIT_MODEL = stringPreferencesKey("edit_model")
        val EXTENDED_THINKING_ENABLED = booleanPreferencesKey("extended_thinking_enabled")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val VOLUME_KEYS_SCROLL = booleanPreferencesKey("volume_keys_scroll")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val IMPORT_DEBUGGING_ENABLED = booleanPreferencesKey("import_debugging_enabled")
        val VOLUME_UNIT_SYSTEM = stringPreferencesKey("volume_unit_system")
        val WEIGHT_UNIT_SYSTEM = stringPreferencesKey("weight_unit_system")
        val GROCERY_VOLUME_UNIT_SYSTEM = stringPreferencesKey("grocery_volume_unit_system")
        val GROCERY_WEIGHT_UNIT_SYSTEM = stringPreferencesKey("grocery_weight_unit_system")
        val START_OF_WEEK = stringPreferencesKey("start_of_week")
        val FAVORITE_TAGS = stringSetPreferencesKey("favorite_tags")
        val TAG_BAR_SIZE = intPreferencesKey("tag_bar_size")
    }

    private val tinkEncryption: TinkEncryption by lazy { TinkEncryption(context) }

    private val apiKeyPrefs by lazy {
        context.getSharedPreferences(API_KEY_PREFS_FILE, Context.MODE_PRIVATE)
    }

    /** Wrapper so "not loaded yet" (null) is distinct from "no key stored". */
    private class LoadedApiKey(val value: String?)

    private val _anthropicApiKey = MutableStateFlow<LoadedApiKey?>(null)

    init {
        // Tink/Keystore initialization and disk reads are slow; keep them off the main thread.
        appScope.launch(ioDispatcher) {
            cleanUpOldEncryptedSharedPreferences()
            val loaded = LoadedApiKey(loadApiKey())
            // A key set while loading wins over the stored value.
            _anthropicApiKey.compareAndSet(null, loaded)
        }
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
            ErrorLog.e(TAG, "Failed to load API key", e)
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

    /** The stored API key. Does not emit until the key has been loaded from disk. */
    override val anthropicApiKey: Flow<String?> = _anthropicApiKey.filterNotNull().map { it.value }

    private fun Preferences.aiModel() = this[Keys.AI_MODEL] ?: AiModels.DEFAULT_MODEL
    private fun Preferences.editModel() = this[Keys.EDIT_MODEL] ?: AiModels.DEFAULT_EDIT_MODEL
    private fun Preferences.thinkingEnabled() = this[Keys.EXTENDED_THINKING_ENABLED] ?: AiModels.DEFAULT_THINKING_ENABLED
    private fun Preferences.keepScreenOn() = this[Keys.KEEP_SCREEN_ON] ?: true
    private fun Preferences.volumeKeysScroll() = this[Keys.VOLUME_KEYS_SCROLL] ?: false
    private fun Preferences.themeMode() = themeModeOf(this[Keys.THEME_MODE], EinkDevices.isThisDevice)
    private fun Preferences.unitSystem(key: Preferences.Key<String>) = this[key]?.let { enumOrNull<UnitSystem>(it) }
    private fun Preferences.volumeUnitSystem() = unitSystem(Keys.VOLUME_UNIT_SYSTEM) ?: UnitSystem.localeDefault()
    private fun Preferences.weightUnitSystem() = unitSystem(Keys.WEIGHT_UNIT_SYSTEM) ?: UnitSystem.localeDefault()

    /** Falls back to the recipe volume unit system when no grocery-specific one has been set. */
    private fun Preferences.groceryVolumeUnitSystem() = unitSystem(Keys.GROCERY_VOLUME_UNIT_SYSTEM) ?: volumeUnitSystem()

    /** Falls back to the recipe weight unit system when no grocery-specific one has been set. */
    private fun Preferences.groceryWeightUnitSystem() = unitSystem(Keys.GROCERY_WEIGHT_UNIT_SYSTEM) ?: weightUnitSystem()
    private fun Preferences.startOfWeek() =
        this[Keys.START_OF_WEEK]?.let { enumOrNull<StartOfWeek>(it) } ?: StartOfWeek.LOCALE_DEFAULT
    private fun Preferences.importDebuggingEnabled() = this[Keys.IMPORT_DEBUGGING_ENABLED] ?: false
    private fun Preferences.tagBarSize() = this[Keys.TAG_BAR_SIZE]?.let(TagBarSize::coerce) ?: TagBarSize.DEFAULT

    private fun <T> setting(read: Preferences.() -> T): Flow<T> =
        context.dataStore.data.map { it.read() }.distinctUntilChanged()

    override val aiModel: Flow<String> = setting { aiModel() }
    override val editModel: Flow<String> = setting { editModel() }
    override val thinkingEnabled: Flow<Boolean> = setting { thinkingEnabled() }
    override val keepScreenOn: Flow<Boolean> = setting { keepScreenOn() }
    override val volumeKeysScroll: Flow<Boolean> = setting { volumeKeysScroll() }
    override val themeMode: Flow<ThemeMode> = setting { themeMode() }
    override val volumeUnitSystem: Flow<UnitSystem> = setting { volumeUnitSystem() }
    override val weightUnitSystem: Flow<UnitSystem> = setting { weightUnitSystem() }
    override val groceryVolumeUnitSystem: Flow<UnitSystem> = setting { groceryVolumeUnitSystem() }
    override val groceryWeightUnitSystem: Flow<UnitSystem> = setting { groceryWeightUnitSystem() }
    override val startOfWeek: Flow<StartOfWeek> = setting { startOfWeek() }
    override val importDebuggingEnabled: Flow<Boolean> = setting { importDebuggingEnabled() }
    override val favoriteTags: Flow<Set<String>> = setting { this[Keys.FAVORITE_TAGS].orEmpty() }
    override val tagBarSize: Flow<Int> = setting { tagBarSize() }

    override val settings: Flow<Settings> = combine(context.dataStore.data, anthropicApiKey) { prefs, apiKey ->
        Settings(
            anthropicApiKey = apiKey,
            aiModel = prefs.aiModel(),
            editModel = prefs.editModel(),
            thinkingEnabled = prefs.thinkingEnabled(),
            keepScreenOn = prefs.keepScreenOn(),
            volumeKeysScroll = prefs.volumeKeysScroll(),
            themeMode = prefs.themeMode(),
            volumeUnitSystem = prefs.volumeUnitSystem(),
            weightUnitSystem = prefs.weightUnitSystem(),
            groceryVolumeUnitSystem = prefs.groceryVolumeUnitSystem(),
            groceryWeightUnitSystem = prefs.groceryWeightUnitSystem(),
            startOfWeek = prefs.startOfWeek(),
            importDebuggingEnabled = prefs.importDebuggingEnabled(),
            tagBarSize = prefs.tagBarSize()
        )
    }.distinctUntilChanged()

    override suspend fun setAnthropicApiKey(apiKey: String) {
        withContext(ioDispatcher) {
            val encrypted = tinkEncryption.encrypt(apiKey)
            apiKeyPrefs.edit(commit = true) { putString(API_KEY_PREF_KEY, encrypted) }
            _anthropicApiKey.value = LoadedApiKey(apiKey)
        }
    }

    override suspend fun clearAnthropicApiKey() {
        withContext(ioDispatcher) {
            apiKeyPrefs.edit(commit = true) { remove(API_KEY_PREF_KEY) }
            _anthropicApiKey.value = LoadedApiKey(null)
        }
    }

    override suspend fun setAiModel(model: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.AI_MODEL] = model
        }
    }

    override suspend fun setEditModel(model: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.EDIT_MODEL] = model
        }
    }

    override suspend fun setThinkingEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[Keys.EXTENDED_THINKING_ENABLED] = enabled
        }
    }

    override suspend fun setKeepScreenOn(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[Keys.KEEP_SCREEN_ON] = enabled
        }
    }

    override suspend fun setVolumeKeysScroll(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[Keys.VOLUME_KEYS_SCROLL] = enabled
        }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { preferences ->
            preferences[Keys.THEME_MODE] = mode.name
        }
    }

    override suspend fun setVolumeUnitSystem(system: UnitSystem) {
        context.dataStore.edit { preferences ->
            preferences[Keys.VOLUME_UNIT_SYSTEM] = system.name
        }
    }

    override suspend fun setWeightUnitSystem(system: UnitSystem) {
        context.dataStore.edit { preferences ->
            preferences[Keys.WEIGHT_UNIT_SYSTEM] = system.name
        }
    }

    override suspend fun setGroceryVolumeUnitSystem(system: UnitSystem) {
        context.dataStore.edit { preferences ->
            preferences[Keys.GROCERY_VOLUME_UNIT_SYSTEM] = system.name
        }
    }

    override suspend fun setGroceryWeightUnitSystem(system: UnitSystem) {
        context.dataStore.edit { preferences ->
            preferences[Keys.GROCERY_WEIGHT_UNIT_SYSTEM] = system.name
        }
    }

    override suspend fun setStartOfWeek(startOfWeek: StartOfWeek) {
        context.dataStore.edit { preferences ->
            preferences[Keys.START_OF_WEEK] = startOfWeek.name
        }
    }

    override suspend fun setImportDebuggingEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[Keys.IMPORT_DEBUGGING_ENABLED] = enabled
        }
    }

    override suspend fun updateFavoriteTags(transform: (Set<String>) -> Set<String>) {
        context.dataStore.edit { preferences ->
            val favorites = transform(preferences[Keys.FAVORITE_TAGS].orEmpty())
            if (favorites.isEmpty()) {
                preferences.remove(Keys.FAVORITE_TAGS)
            } else {
                preferences[Keys.FAVORITE_TAGS] = favorites
            }
        }
    }

    override suspend fun setTagBarSize(size: Int) {
        context.dataStore.edit { preferences ->
            preferences[Keys.TAG_BAR_SIZE] = TagBarSize.coerce(size)
        }
    }

    companion object {
        private const val TAG = "SettingsDataStore"
        private const val API_KEY_PREFS_FILE = "tink_encrypted_api_key"
        private const val API_KEY_PREF_KEY = "encrypted_api_key"

        /** The stored theme, or the device's default until the user has picked one. */
        internal fun themeModeOf(stored: String?, einkDevice: Boolean): ThemeMode =
            stored?.let { enumOrNull<ThemeMode>(it) } ?: ThemeMode.defaultFor(einkDevice)
    }
}
