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
import com.lionotter.recipes.domain.model.AiModel
import com.lionotter.recipes.domain.model.AiProviders
import com.lionotter.recipes.domain.model.ModelKind

/** The model that generates recipe images (from [models], those of providers with a key), and whether to generate them. */
@Composable
fun ImageGenerationSection(
    models: List<AiModel>,
    imageModel: AiModel?,
    onImageModelChange: (String) -> Unit,
    imageGenerationEnabled: Boolean,
    onImageGenerationChange: (Boolean) -> Unit,
    onGenerateMissingImagesClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.recipe_images),
            style = MaterialTheme.typography.titleMedium
        )

        if (models.isEmpty()) {
            val providers = AiProviders.ALL.filter { it.models(ModelKind.IMAGE).isNotEmpty() }.joinToString { it.name }
            Text(
                text = stringResource(R.string.recipe_images_need_key, providers),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = stringResource(R.string.recipe_images_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            ModelDropdown(
                label = stringResource(R.string.image_model),
                currentModel = imageModel,
                models = models,
                onModelChange = onImageModelChange
            )

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
                modifier = Modifier.fillMaxWidth(),
                enabled = imageGenerationEnabled
            ) {
                Text(stringResource(R.string.generate_images_for_existing_recipes))
            }
        }
    }
}
