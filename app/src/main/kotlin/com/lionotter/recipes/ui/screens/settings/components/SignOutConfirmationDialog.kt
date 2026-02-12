package com.lionotter.recipes.ui.screens.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lionotter.recipes.R
import com.lionotter.recipes.ui.screens.settings.ZipOperationState

@Composable
fun SignOutConfirmationDialog(
    exportState: ZipOperationState,
    onConfirm: () -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit
) {
    val isExporting = exportState is ZipOperationState.Exporting
    val exportComplete = exportState is ZipOperationState.ExportComplete

    val message = buildString {
        append(stringResource(R.string.sign_out_confirmation_message))
        if (isExporting) {
            append("\n\n")
            append(stringResource(R.string.exporting_recipes))
        } else if (exportComplete) {
            val state = exportState as ZipOperationState.ExportComplete
            append("\n\n")
            append(stringResource(R.string.exported_recipes, state.exportedCount))
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isExporting) onDismiss() },
        title = { Text(stringResource(R.string.sign_out_confirmation_title)) },
        text = { Text(message) },
        confirmButton = {
            // Stacked vertically per Material 3 guidance for 3+ actions
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(
                    onClick = onConfirm,
                    enabled = !isExporting
                ) {
                    Text(
                        stringResource(R.string.sign_out_confirm),
                        color = if (!isExporting) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                    )
                }
                TextButton(
                    onClick = onExport,
                    enabled = !isExporting
                ) {
                    Text(stringResource(R.string.export_first))
                }
                TextButton(
                    onClick = onDismiss,
                    enabled = !isExporting
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )
}
