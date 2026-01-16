package com.lionotter.recipes.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val ANTHROPIC_API_KEY = stringPreferencesKey("anthropic_api_key")
        val AI_MODEL = stringPreferencesKey("ai_model")
    }

    val anthropicApiKey: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[Keys.ANTHROPIC_API_KEY]
    }

    val aiModel: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[Keys.AI_MODEL] ?: "claude-opus-4-5"
    }

    suspend fun setAnthropicApiKey(apiKey: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.ANTHROPIC_API_KEY] = apiKey
        }
    }

    suspend fun clearAnthropicApiKey() {
        context.dataStore.edit { preferences ->
            preferences.remove(Keys.ANTHROPIC_API_KEY)
        }
    }

    suspend fun setAiModel(model: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.AI_MODEL] = model
        }
    }
}
