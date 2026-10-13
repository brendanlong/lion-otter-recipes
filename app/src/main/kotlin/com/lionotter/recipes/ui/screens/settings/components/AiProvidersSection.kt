package com.lionotter.recipes.ui.screens.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lionotter.recipes.R
import com.lionotter.recipes.ui.components.text
import com.lionotter.recipes.ui.screens.settings.ApiKeyField

/** An API key section for every AI provider; [apiKeys] holds the stored keys by provider ID. */
@Composable
fun AiProvidersSection(
    fields: List<ApiKeyField>,
    apiKeys: Map<String, String>,
    onClearClick: (providerId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = stringResource(R.string.ai_providers),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = stringResource(R.string.ai_providers_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        for (field in fields) {
            val provider = field.provider
            val input by field.input.collectAsStateWithLifecycle()
            val error by field.error.collectAsStateWithLifecycle()
            ApiKeySection(
                title = stringResource(R.string.provider_api_key, provider.name),
                keyPrefix = provider.keyPrefix,
                getKeyLabel = stringResource(R.string.get_provider_api_key, provider.name),
                getKeyUrl = provider.keyUrl,
                currentApiKey = apiKeys[provider.id],
                apiKeyInput = input,
                onApiKeyInputChange = field::onInputChange,
                onSaveClick = field::save,
                onClearClick = { onClearClick(provider.id) },
                apiKeyError = error?.text()
            )
        }
    }
}
