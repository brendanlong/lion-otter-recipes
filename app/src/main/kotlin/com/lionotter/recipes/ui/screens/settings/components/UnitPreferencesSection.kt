package com.lionotter.recipes.ui.screens.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.model.UnitSystem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnitPreferencesSection(
    volumeUnitSystem: UnitSystem,
    onVolumeUnitSystemChange: (UnitSystem) -> Unit,
    weightUnitSystem: UnitSystem,
    onWeightUnitSystemChange: (UnitSystem) -> Unit,
    groceryVolumeUnitSystem: UnitSystem,
    onGroceryVolumeUnitSystemChange: (UnitSystem) -> Unit,
    groceryWeightUnitSystem: UnitSystem,
    onGroceryWeightUnitSystemChange: (UnitSystem) -> Unit
) {
    val options = listOf(
        UnitSystem.CUSTOMARY to stringResource(R.string.unit_system_customary),
        UnitSystem.METRIC to stringResource(R.string.unit_system_metric)
    )

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = stringResource(R.string.unit_preferences),
            style = MaterialTheme.typography.titleMedium
        )

        // Recipe units subsection
        Text(
            text = stringResource(R.string.recipe_units),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Volume unit system
        UnitSystemSelector(
            label = stringResource(R.string.volume_units),
            description = stringResource(R.string.volume_units_description),
            selectedSystem = volumeUnitSystem,
            onSystemChange = onVolumeUnitSystemChange,
            options = options
        )

        // Weight unit system
        UnitSystemSelector(
            label = stringResource(R.string.weight_units),
            description = stringResource(R.string.weight_units_description),
            selectedSystem = weightUnitSystem,
            onSystemChange = onWeightUnitSystemChange,
            options = options
        )

        // Grocery list units subsection
        Text(
            text = stringResource(R.string.grocery_list_units),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = stringResource(R.string.grocery_list_units_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Grocery volume unit system
        UnitSystemSelector(
            label = stringResource(R.string.volume_units),
            description = stringResource(R.string.volume_units_description),
            selectedSystem = groceryVolumeUnitSystem,
            onSystemChange = onGroceryVolumeUnitSystemChange,
            options = options
        )

        // Grocery weight unit system
        UnitSystemSelector(
            label = stringResource(R.string.weight_units),
            description = stringResource(R.string.weight_units_description),
            selectedSystem = groceryWeightUnitSystem,
            onSystemChange = onGroceryWeightUnitSystemChange,
            options = options
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitSystemSelector(
    label: String,
    description: String,
    selectedSystem: UnitSystem,
    onSystemChange: (UnitSystem) -> Unit,
    options: List<Pair<UnitSystem, String>>
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            options.forEachIndexed { index, (system, systemLabel) ->
                SegmentedButton(
                    selected = selectedSystem == system,
                    onClick = { onSystemChange(system) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = options.size
                    )
                ) {
                    Text(systemLabel)
                }
            }
        }
    }
}
