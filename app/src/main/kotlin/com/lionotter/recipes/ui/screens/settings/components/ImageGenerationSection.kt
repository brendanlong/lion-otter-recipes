package com.lionotter.recipes.ui.screens.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.lionotter.recipes.R
import com.lionotter.recipes.data.ai.ApiKeyValidator

/** The OpenAI API key used to generate recipe images, and whether to generate them. */
@Composable
fun ImageGenerationSection(
    currentApiKey: String?,
    apiKeyInput: String,
    onApiKeyInputChange: (String) -> Unit,
    onSaveClick: () -> Unit,
    onClearClick: () -> Unit,
    apiKeyError: String?,
    imageGenerationEnabled: Boolean,
    onImageGenerationChange: (Boolean) -> Unit,
    onGenerateMissingImagesClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ApiKeySection(
            title = stringResource(R.string.recipe_images),
            description = stringResource(R.string.openai_api_key_storage_info),
            keyPrefix = ApiKeyValidator.OPENAI_API_KEY_PREFIX,
            getKeyLabel = stringResource(R.string.get_openai_api_key),
            getKeyUrl = "https://platform.openai.com/api-keys",
            currentApiKey = currentApiKey,
            apiKeyInput = apiKeyInput,
            onApiKeyInputChange = onApiKeyInputChange,
            onSaveClick = onSaveClick,
            onClearClick = onClearClick,
            apiKeyError = apiKeyError
        )

        if (currentApiKey != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = imageGenerationEnabled,
                        role = Role.Switch,
                        onValueChange = onImageGenerationChange
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.generate_missing_images),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(R.string.generate_missing_images_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = imageGenerationEnabled,
                    onCheckedChange = null
                )
            }

            OutlinedButton(
                onClick = onGenerateMissingImagesClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.generate_images_for_existing_recipes))
            }
        }
    }
}
