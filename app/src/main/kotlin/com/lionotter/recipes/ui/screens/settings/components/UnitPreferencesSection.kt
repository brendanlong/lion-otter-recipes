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
    onWeightUnitSystemChange: (UnitSystem) -> Unit
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

        // Volume unit system
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.volume_units),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(R.string.volume_units_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                options.forEachIndexed { index, (system, label) ->
                    SegmentedButton(
                        selected = volumeUnitSystem == system,
                        onClick = { onVolumeUnitSystemChange(system) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = options.size
                        )
                    ) {
                        Text(label)
                    }
                }
            }
        }

        // Weight unit system
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.weight_units),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(R.string.weight_units_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                options.forEachIndexed { index, (system, label) ->
                    SegmentedButton(
                        selected = weightUnitSystem == system,
                        onClick = { onWeightUnitSystemChange(system) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = options.size
                        )
                    ) {
                        Text(label)
                    }
                }
            }
        }
    }
}
