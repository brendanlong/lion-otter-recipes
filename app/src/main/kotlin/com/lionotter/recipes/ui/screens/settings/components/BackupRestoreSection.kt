package com.lionotter.recipes.ui.screens.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lionotter.recipes.R
import com.lionotter.recipes.ui.components.ProgressCard
import com.lionotter.recipes.ui.screens.settings.ZipOperationState

@Composable
fun BackupRestoreSection(
    operationState: ZipOperationState,
    onExportClick: () -> Unit,
    onImportClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.backup_restore),
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            text = stringResource(R.string.backup_restore_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val isExporting = operationState is ZipOperationState.Exporting

        if (isExporting) {
            ProgressCard(
                message = stringResource(R.string.exporting_recipes)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onExportClick,
                enabled = !isExporting,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.FileUpload,
                    contentDescription = null
                )
                Text("  " + stringResource(R.string.export_recipes))
            }

            OutlinedButton(
                onClick = onImportClick,
                enabled = !isExporting,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.FileDownload,
                    contentDescription = null
                )
                Text("  " + stringResource(R.string.import_recipes))
            }
        }
    }
}
