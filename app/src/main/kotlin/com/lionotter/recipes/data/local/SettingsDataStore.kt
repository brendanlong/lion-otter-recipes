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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.lionotter.recipes.di.ApplicationScope
import com.lionotter.recipes.domain.model.AiProviders
import com.lionotter.recipes.domain.model.ModelRole
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
 * deletion also removes the API keys (the user's credentials) and the favorite tags (the user's
 * content); see [clearUserData].
 */
@Singleton
class SettingsDataStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @ApplicationScope appScope: CoroutineScope,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : AppSettings, PerUserLocalData {
    override val scope = LocalDataScope.DEVICE

    /** Removes the API keys and the favorite tags; the other settings are kept. */
    override suspend fun clearUserData() {
        var failure: Exception? = null
        // Each store is cleared even if another fails
        suspend fun attempt(clear: suspend () -> Unit) {
            try {
                clear()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure?.let(e::addSuppressed)
                failure = e
            }
        }
        for (provider in AiProviders.ALL) attempt { clearApiKey(provider.id) }
        attempt { context.dataStore.edit { it.remove(Keys.FAVORITE_TAGS) } }
        failure?.let { throw it }
    }

    private object Keys {
        val AI_MODEL = stringPreferencesKey("ai_model")
        val EDIT_MODEL = stringPreferencesKey("edit_model")
        val IMAGE_MODEL = stringPreferencesKey("image_model")

        fun model(role: ModelRole) = when (role) {
            ModelRole.IMPORT -> AI_MODEL
            ModelRole.EDIT -> EDIT_MODEL
            ModelRole.IMAGE -> IMAGE_MODEL
        }
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
        val IMAGE_GENERATION_ENABLED = booleanPreferencesKey("image_generation_enabled")
    }

    private val tinkEncryption: TinkEncryption by lazy { TinkEncryption(context) }

    private val apiKeyPrefs by lazy {
        context.getSharedPreferences(API_KEY_PREFS_FILE, Context.MODE_PRIVATE)
    }

    /** Provider ID to key, in the order they were added; null until loaded from disk. */
    private val loadedApiKeys = MutableStateFlow<Map<String, String>?>(null)

    /** Serializes loading and changing the keys, so the stored order and keys stay consistent. */
    private val apiKeyMutex = Mutex()

    init {
        // Tink/Keystore initialization and disk reads are slow; keep them off the main thread.
        appScope.launch(ioDispatcher) {
            cleanUpOldEncryptedSharedPreferences()
            apiKeyMutex.withLock { ensureApiKeysLoaded() }
        }
    }

    /**
     * Reads the keys from disk (a key that can't be decrypted is logged and treated as absent).
     * Keys stored before the order was kept come after the ordered ones, in [AiProviders.ALL] order.
     */
    private fun ensureApiKeysLoaded(): Map<String, String> {
        loadedApiKeys.value?.let { return it }
        val keys = AiProviders.ALL.mapNotNull { provider ->
            val stored = try {
                apiKeyPrefs.getString(prefKeyFor(provider.id), null)?.let { tinkEncryption.decrypt(it) }
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Failed to load the ${provider.id} API key", e)
                null
            }
            stored?.let { provider.id to it }
        }.toMap()
        val ordered = orderedApiKeys(keys, storedProviderOrder())
        loadedApiKeys.value = ordered
        return ordered
    }

    private fun storedProviderOrder(): List<String> =
        apiKeyPrefs.getString(PROVIDER_ORDER_PREF_KEY, null)?.split(',')?.filter { it.isNotEmpty() }.orEmpty()

    private fun prefKeyFor(providerId: String) = when (providerId) {
        AiProviders.ANTHROPIC_ID -> API_KEY_PREF_KEY
        AiProviders.OPENAI_ID -> OPENAI_API_KEY_PREF_KEY
        else -> "encrypted_api_key_$providerId"
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

    override val apiKeys: Flow<Map<String, String>> = loadedApiKeys.filterNotNull()

    private fun Preferences.thinkingEnabled() = this[Keys.EXTENDED_THINKING_ENABLED] ?: AiProviders.DEFAULT_THINKING_ENABLED
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
    private fun Preferences.imageGenerationEnabled() = this[Keys.IMAGE_GENERATION_ENABLED] ?: true
    private fun Preferences.tagBarSize() = this[Keys.TAG_BAR_SIZE]?.let(TagBarSize::coerce) ?: TagBarSize.DEFAULT

    private fun <T> setting(read: Preferences.() -> T): Flow<T> =
        context.dataStore.data.map { it.read() }.distinctUntilChanged()

    override fun selectedModel(role: ModelRole): Flow<String?> = setting { this[Keys.model(role)] }
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
    override val imageGenerationEnabled: Flow<Boolean> = setting { imageGenerationEnabled() }

    override val settings: Flow<Settings> = combine(context.dataStore.data, apiKeys) { prefs, keys ->
        Settings(
            apiKeys = keys,
            models = ModelRole.entries.mapNotNull { role ->
                AiProviders.resolve(role, prefs[Keys.model(role)], keys)?.let { role to it.model }
            }.toMap(),
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
            imageGenerationEnabled = prefs.imageGenerationEnabled(),
            tagBarSize = prefs.tagBarSize()
        )
    }.distinctUntilChanged()

    override suspend fun setApiKey(providerId: String, apiKey: String) {
        withContext(ioDispatcher) {
            apiKeyMutex.withLock {
                val keys = ensureApiKeysLoaded()
                val encrypted = tinkEncryption.encrypt(apiKey)
                // From the loaded keys, which already place keys stored before the order was kept
                val order = keys.keys.toList().let { if (providerId in it) it else it + providerId }
                apiKeyPrefs.edit(commit = true) {
                    putString(prefKeyFor(providerId), encrypted)
                    putString(PROVIDER_ORDER_PREF_KEY, order.joinToString(","))
                }
                loadedApiKeys.value = keys + (providerId to apiKey)
            }
        }
    }

    override suspend fun clearApiKey(providerId: String) {
        withContext(ioDispatcher) {
            apiKeyMutex.withLock {
                val keys = ensureApiKeysLoaded()
                apiKeyPrefs.edit(commit = true) {
                    remove(prefKeyFor(providerId))
                    putString(PROVIDER_ORDER_PREF_KEY, (keys.keys - providerId).joinToString(","))
                }
                loadedApiKeys.value = keys - providerId
            }
        }
        // The other providers' defaults take over, also if this provider is added again later
        context.dataStore.edit { preferences ->
            for (role in ModelRole.entries) {
                val key = Keys.model(role)
                if (AiProviders.findModel(preferences[key])?.providerId == providerId) preferences.remove(key)
            }
        }
    }

    override suspend fun setImageGenerationEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[Keys.IMAGE_GENERATION_ENABLED] = enabled
        }
    }

    override suspend fun setSelectedModel(role: ModelRole, modelId: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.model(role)] = modelId
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
        private const val OPENAI_API_KEY_PREF_KEY = "encrypted_openai_api_key"

        /** Comma-separated provider IDs in the order their keys were added (not secret, so not encrypted). */
        private const val PROVIDER_ORDER_PREF_KEY = "provider_order"

        /** [keys] in the stored [order]; keys missing from it (stored before the order was kept) come last, in [keys] order. */
        internal fun orderedApiKeys(keys: Map<String, String>, order: List<String>): Map<String, String> =
            (order.filter { it in keys } + keys.keys.filter { it !in order }).associateWith { keys.getValue(it) }

        /** The stored theme, or the device's default until the user has picked one. */
        internal fun themeModeOf(stored: String?, einkDevice: Boolean): ThemeMode =
            stored?.let { enumOrNull<ThemeMode>(it) } ?: ThemeMode.defaultFor(einkDevice)
    }
}
