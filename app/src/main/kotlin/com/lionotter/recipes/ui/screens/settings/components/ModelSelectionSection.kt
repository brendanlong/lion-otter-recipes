package com.lionotter.recipes.ui.screens.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lionotter.recipes.R

private val MODELS
    @Composable get() = listOf(
        "claude-opus-4-6" to stringResource(R.string.model_opus_4_6),
        "claude-opus-4-5" to stringResource(R.string.model_opus),
        "claude-sonnet-4-6" to stringResource(R.string.model_sonnet_4_6),
        "claude-sonnet-4-5" to stringResource(R.string.model_sonnet),
        "claude-haiku-4-5" to stringResource(R.string.model_haiku)
    )

/**
 * Model selection section for the settings screen, with separate import and edit model dropdowns.
 */
@Composable
fun ModelSelectionSection(
    currentModel: String,
    onModelChange: (String) -> Unit,
    currentEditModel: String,
    onEditModelChange: (String) -> Unit,
    thinkingEnabled: Boolean,
    onThinkingChange: (Boolean) -> Unit
) {
    val models = MODELS

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.ai_models),
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            text = stringResource(R.string.ai_model_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ModelDropdown(
            label = stringResource(R.string.import_model),
            description = stringResource(R.string.import_model_description),
            currentModel = currentModel,
            models = models,
            onModelChange = onModelChange
        )

        ModelDropdown(
            label = stringResource(R.string.edit_model),
            description = stringResource(R.string.edit_model_description),
            currentModel = currentEditModel,
            models = models,
            onModelChange = onEditModelChange
        )

        ThinkingToggle(
            enabled = thinkingEnabled,
            onEnabledChange = onThinkingChange
        )
    }
}

/**
 * Single model selection section used on the edit recipe screen.
 */
@Composable
fun SingleModelSelectionSection(
    currentModel: String,
    onModelChange: (String) -> Unit,
    thinkingEnabled: Boolean,
    onThinkingChange: (Boolean) -> Unit
) {
    val models = MODELS

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.ai_model),
            style = MaterialTheme.typography.titleMedium
        )

        ModelDropdown(
            currentModel = currentModel,
            models = models,
            onModelChange = onModelChange
        )

        ThinkingToggle(
            enabled = thinkingEnabled,
            onEnabledChange = onThinkingChange
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(
    currentModel: String,
    models: List<Pair<String, String>>,
    onModelChange: (String) -> Unit,
    label: String? = null,
    description: String? = null
) {
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge
            )
        }
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = models.find { it.first == currentModel }?.second ?: currentModel,
                onValueChange = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                models.forEach { (modelId, displayName) ->
                    DropdownMenuItem(
                        text = { Text(displayName) },
                        onClick = {
                            onModelChange(modelId)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ThinkingToggle(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.thinking),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(R.string.thinking_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange
        )
    }
}
