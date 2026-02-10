package com.lionotter.recipes.ui.screens.recipedetail.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.model.MeasurementPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MeasurementToggle(
    selectedPreference: MeasurementPreference,
    onPreferenceChange: (MeasurementPreference) -> Unit
) {
    val options = listOf(
        MeasurementPreference.DEFAULT to stringResource(R.string.original),
        MeasurementPreference.VOLUME to stringResource(R.string.volume),
        MeasurementPreference.WEIGHT to stringResource(R.string.weight)
    )

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.units),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                options.forEachIndexed { index, (preference, label) ->
                    SegmentedButton(
                        selected = selectedPreference == preference,
                        onClick = { onPreferenceChange(preference) },
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
