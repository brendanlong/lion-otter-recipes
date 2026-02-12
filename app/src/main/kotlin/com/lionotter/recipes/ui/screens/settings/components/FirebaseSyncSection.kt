package com.lionotter.recipes.ui.screens.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import com.lionotter.recipes.ui.screens.firebase.FirebaseSyncUiState
import com.lionotter.recipes.ui.screens.settings.ZipOperationState

@Composable
fun FirebaseSyncSection(
    uiState: FirebaseSyncUiState,
    syncEnabled: Boolean,
    isGoogleSignedIn: Boolean,
    exportState: ZipOperationState,
    onSignInClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onExportClick: () -> Unit,
    onEnableSyncClick: () -> Unit,
    onDisableSyncClick: () -> Unit,
) {
    var showSignOutDialog by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (isGoogleSignedIn) Icons.Default.Cloud else Icons.Default.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = stringResource(R.string.cloud_sync),
                style = MaterialTheme.typography.titleMedium
            )
        }

        when (uiState) {
            is FirebaseSyncUiState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }

            is FirebaseSyncUiState.SignedOut -> {
                Text(
                    text = stringResource(R.string.sign_in_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onSignInClick) {
                    Text(stringResource(R.string.sign_in_with_google))
                }
            }

            is FirebaseSyncUiState.SignedIn -> {
                // Show sync toggle only for Google-signed-in users
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.enable_sync),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Switch(
                        checked = syncEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) onEnableSyncClick() else onDisableSyncClick()
                        }
                    )
                }

                Text(
                    text = if (syncEnabled) {
                        stringResource(R.string.sync_enabled_description)
                    } else {
                        stringResource(R.string.sync_disabled_description)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                TextButton(onClick = { showSignOutDialog = true }) {
                    Text(stringResource(R.string.sign_out))
                }
            }

            is FirebaseSyncUiState.Error -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = uiState.message,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                Button(onClick = onSignInClick) {
                    Text(stringResource(R.string.sign_in_with_google))
                }
            }
        }
    }

    if (showSignOutDialog) {
        SignOutConfirmationDialog(
            exportState = exportState,
            onConfirm = {
                showSignOutDialog = false
                onSignOutClick()
            },
            onExport = onExportClick,
            onDismiss = { showSignOutDialog = false }
        )
    }
}
