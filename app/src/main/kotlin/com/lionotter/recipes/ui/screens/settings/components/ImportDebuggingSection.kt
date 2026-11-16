package com.lionotter.recipes.ui.screens.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lionotter.recipes.R

@Composable
fun ImportDebuggingSection(
    importDebuggingEnabled: Boolean,
    onImportDebuggingChange: (Boolean) -> Unit,
    onViewDebugDataClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.import_debugging),
            style = MaterialTheme.typography.titleMedium
        )

        SwitchRow(
            title = stringResource(R.string.import_debugging),
            description = stringResource(R.string.import_debugging_description),
            checked = importDebuggingEnabled,
            onCheckedChange = onImportDebuggingChange
        )

        if (importDebuggingEnabled) {
            Button(
                onClick = onViewDebugDataClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.view_import_debug_data))
            }
        }
    }
}
