package com.lionotter.recipes.ui.screens.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.lionotter.recipes.data.remote.DriveFolder

sealed class FolderPickerState {
    object Loading : FolderPickerState()
    data class Loaded(val folders: List<DriveFolder>) : FolderPickerState()
    data class Error(val message: String) : FolderPickerState()
}

sealed class FolderSelection {
    object CreateNew : FolderSelection()
    data class Existing(val folder: DriveFolder) : FolderSelection()
}

@Composable
fun FolderPickerDialog(
    state: FolderPickerState,
    onDismiss: () -> Unit,
    onConfirm: (FolderSelection) -> Unit
) {
    var selection by remember { mutableStateOf<FolderSelection>(FolderSelection.CreateNew) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_sync_folder)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.select_sync_folder_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                when (state) {
                    is FolderPickerState.Loading -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = stringResource(R.string.loading_folders),
                                modifier = Modifier.padding(start = 12.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    is FolderPickerState.Loaded -> {
                        // "Create new folder" option
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selection = FolderSelection.CreateNew }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selection is FolderSelection.CreateNew,
                                onClick = { selection = FolderSelection.CreateNew }
                            )
                            Icon(
                                imageVector = Icons.Default.CreateNewFolder,
                                contentDescription = null,
                                modifier = Modifier.padding(horizontal = 8.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(R.string.create_new_folder),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        // Existing folders
                        if (state.folders.isNotEmpty()) {
                            LazyColumn {
                                items(state.folders) { folder ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selection = FolderSelection.Existing(folder)
                                            }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = selection is FolderSelection.Existing &&
                                                (selection as FolderSelection.Existing).folder.id == folder.id,
                                            onClick = {
                                                selection = FolderSelection.Existing(folder)
                                            }
                                        )
                                        Icon(
                                            imageVector = Icons.Default.Folder,
                                            contentDescription = null,
                                            modifier = Modifier.padding(horizontal = 8.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = folder.name,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        }
                    }

                    is FolderPickerState.Error -> {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            val enabled = state is FolderPickerState.Loaded
            TextButton(
                onClick = { onConfirm(selection) },
                enabled = enabled
            ) {
                Text(
                    if (selection is FolderSelection.CreateNew) {
                        stringResource(R.string.create_new_folder)
                    } else {
                        stringResource(R.string.use_selected_folder)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
