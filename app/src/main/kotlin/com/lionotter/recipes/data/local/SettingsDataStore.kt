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
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val AI_MODEL = stringPreferencesKey("ai_model")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
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

    val keepScreenOn: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[Keys.KEEP_SCREEN_ON] ?: true
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

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[Keys.KEEP_SCREEN_ON] = enabled
        }
    }
}
