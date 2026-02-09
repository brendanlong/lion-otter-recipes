package com.lionotter.recipes.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.lionotter.recipes.data.remote.AnthropicService
import com.lionotter.recipes.domain.model.ThemeMode
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val AI_MODEL = stringPreferencesKey("ai_model")
        val EXTENDED_THINKING_ENABLED = booleanPreferencesKey("extended_thinking_enabled")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val GOOGLE_DRIVE_SYNC_ENABLED = booleanPreferencesKey("google_drive_sync_enabled")
        val GOOGLE_DRIVE_SYNC_FOLDER_ID = stringPreferencesKey("google_drive_sync_folder_id")
        val GOOGLE_DRIVE_SYNC_FOLDER_NAME = stringPreferencesKey("google_drive_sync_folder_name")
        val GOOGLE_DRIVE_LAST_SYNC_TIMESTAMP = stringPreferencesKey("google_drive_last_sync_timestamp")
        val IMPORT_DEBUGGING_ENABLED = booleanPreferencesKey("import_debugging_enabled")
        const val ENCRYPTED_API_KEY = "anthropic_api_key"
    }

    private val encryptedPrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "secure_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val _anthropicApiKey = MutableStateFlow<String?>(null)

    init {
        // Load the API key from encrypted storage on initialization
        _anthropicApiKey.value = encryptedPrefs.getString(Keys.ENCRYPTED_API_KEY, null)
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

    val googleDriveSyncEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[Keys.GOOGLE_DRIVE_SYNC_ENABLED] ?: false
    }

    val googleDriveSyncFolderId: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[Keys.GOOGLE_DRIVE_SYNC_FOLDER_ID]
    }

    val googleDriveSyncFolderName: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[Keys.GOOGLE_DRIVE_SYNC_FOLDER_NAME]
    }

    val googleDriveLastSyncTimestamp: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[Keys.GOOGLE_DRIVE_LAST_SYNC_TIMESTAMP]
    }

    val importDebuggingEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[Keys.IMPORT_DEBUGGING_ENABLED] ?: false
    }

    suspend fun setAnthropicApiKey(apiKey: String) {
        withContext(Dispatchers.IO) {
            encryptedPrefs.edit().putString(Keys.ENCRYPTED_API_KEY, apiKey).apply()
            _anthropicApiKey.value = apiKey
        }
    }

    suspend fun clearAnthropicApiKey() {
        withContext(Dispatchers.IO) {
            encryptedPrefs.edit().remove(Keys.ENCRYPTED_API_KEY).apply()
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

    suspend fun setGoogleDriveSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[Keys.GOOGLE_DRIVE_SYNC_ENABLED] = enabled
        }
    }

    suspend fun setGoogleDriveSyncFolder(folderId: String, folderName: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.GOOGLE_DRIVE_SYNC_FOLDER_ID] = folderId
            preferences[Keys.GOOGLE_DRIVE_SYNC_FOLDER_NAME] = folderName
        }
    }

    suspend fun clearGoogleDriveSyncFolder() {
        context.dataStore.edit { preferences ->
            preferences.remove(Keys.GOOGLE_DRIVE_SYNC_FOLDER_ID)
            preferences.remove(Keys.GOOGLE_DRIVE_SYNC_FOLDER_NAME)
            preferences.remove(Keys.GOOGLE_DRIVE_SYNC_ENABLED)
            preferences.remove(Keys.GOOGLE_DRIVE_LAST_SYNC_TIMESTAMP)
        }
    }

    suspend fun setGoogleDriveLastSyncTimestamp(timestamp: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.GOOGLE_DRIVE_LAST_SYNC_TIMESTAMP] = timestamp
        }
    }

    suspend fun setImportDebuggingEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[Keys.IMPORT_DEBUGGING_ENABLED] = enabled
        }
    }
}
