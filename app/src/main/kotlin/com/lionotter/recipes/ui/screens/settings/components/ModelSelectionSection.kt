package com.lionotter.recipes.ui.screens.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.model.AiModel
import com.lionotter.recipes.domain.model.AiProviders
import com.lionotter.recipes.domain.model.ModelTier

/**
 * Model selection section for the settings screen, with separate import and edit model dropdowns
 * offering [models] (the text models of the providers with a key).
 */
@Composable
fun ModelSelectionSection(
    models: List<AiModel>,
    importModel: AiModel?,
    onImportModelChange: (String) -> Unit,
    editModel: AiModel?,
    onEditModelChange: (String) -> Unit,
    thinkingEnabled: Boolean,
    onThinkingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.ai_models),
            style = MaterialTheme.typography.titleMedium
        )

        if (models.isEmpty()) {
            Text(
                text = stringResource(R.string.ai_models_need_key),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        Text(
            text = stringResource(R.string.ai_model_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ModelDropdown(
            label = stringResource(R.string.import_model),
            description = stringResource(R.string.import_model_description),
            currentModel = importModel,
            models = models,
            onModelChange = onImportModelChange
        )

        ModelDropdown(
            label = stringResource(R.string.edit_model),
            description = stringResource(R.string.edit_model_description),
            currentModel = editModel,
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
    currentModel: AiModel?,
    models: List<AiModel>,
    onModelChange: (String) -> Unit,
    thinkingEnabled: Boolean,
    onThinkingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

@Composable
private fun ModelTier.label(): String = stringResource(
    when (this) {
        ModelTier.MOST_CAPABLE -> R.string.model_tier_most_capable
        ModelTier.BEST_QUALITY -> R.string.model_tier_best_quality
        ModelTier.BALANCED -> R.string.model_tier_balanced
        ModelTier.FASTEST -> R.string.model_tier_fastest
    }
)

@Composable
private fun AiModel.label(): String = stringResource(R.string.model_name_with_tier, name, tier.label())

private fun AiModel.providerName(): String = AiProviders.provider(providerId)?.name ?: providerId

/**
 * A dropdown of [models] that can be searched by typing (by model, provider or tier), since
 * several providers make a long list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelDropdown(
    currentModel: AiModel?,
    models: List<AiModel>,
    onModelChange: (String) -> Unit,
    label: String? = null,
    description: String? = null
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val labels = models.associate { it.id to it.label() }
    val matches = if (query.isBlank()) {
        models
    } else {
        models.filter { model ->
            listOf(labels.getValue(model.id), model.providerName()).any { it.contains(query.trim(), ignoreCase = true) }
        }
    }
    val currentLabel = currentModel?.let { labels[it.id] ?: it.label() }.orEmpty()

    fun close() {
        expanded = false
        query = ""
        // Otherwise typing into the closed field would edit the selected model's name
        focusManager.clearFocus()
    }

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
            onExpandedChange = { if (it) expanded = true else close() }
        ) {
            OutlinedTextField(
                value = if (expanded) query else currentLabel,
                onValueChange = {
                    query = if (expanded) it else it.removePrefix(currentLabel)
                    expanded = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                placeholder = { Text(if (currentLabel.isEmpty()) stringResource(R.string.search_models) else currentLabel) },
                supportingText = currentModel?.takeIf { !expanded }?.let { { Text(it.providerName()) } },
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = ::close
            ) {
                if (matches.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.no_matching_models)) },
                        onClick = {},
                        enabled = false
                    )
                }
                matches.forEach { model ->
                    val selected = model.id == currentModel?.id
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(labels.getValue(model.id))
                                Text(
                                    text = model.providerName(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        leadingIcon = if (selected) {
                            { Icon(Icons.Default.Check, contentDescription = stringResource(R.string.selected_model)) }
                        } else {
                            null
                        },
                        onClick = {
                            onModelChange(model.id)
                            close()
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
