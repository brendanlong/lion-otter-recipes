package com.lionotter.recipes.ui.screens.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lionotter.recipes.R
import com.lionotter.recipes.data.sync.SyncConnectionState
import com.lionotter.recipes.ui.components.ErrorCard
import com.lionotter.recipes.ui.components.ProgressCard
import com.lionotter.recipes.ui.components.StatusCard
import com.lionotter.recipes.ui.screens.firebase.FirebaseSyncUiState

@Composable
fun FirebaseSyncSection(
    uiState: FirebaseSyncUiState,
    syncEnabled: Boolean,
    connectionState: SyncConnectionState,
    errorMessage: String?,
    onSignInClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onEnableSyncClick: () -> Unit,
    onDisableSyncClick: () -> Unit,
    onDismissError: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.cloud_sync),
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            text = stringResource(R.string.cloud_sync_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (errorMessage != null) {
            ErrorCard(message = errorMessage)
        }

        when (uiState) {
            is FirebaseSyncUiState.Loading -> {
                ProgressCard(message = stringResource(R.string.checking_sign_in_status))
            }

            is FirebaseSyncUiState.SignedIn -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cloud,
                                contentDescription = stringResource(R.string.signed_in),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "  " + stringResource(R.string.signed_in),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        OutlinedButton(onClick = onSignOutClick) {
                            Text(stringResource(R.string.disconnect))
                        }
                    }
                }

                // Sync toggle section
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.cloud_sync_toggle),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = stringResource(R.string.cloud_sync_toggle_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = syncEnabled,
                                onCheckedChange = { enabled ->
                                    if (enabled) onEnableSyncClick() else onDisableSyncClick()
                                }
                            )
                        }

                        if (syncEnabled) {
                            SyncStatusIndicator(connectionState = connectionState)
                        }
                    }
                }
            }

            is FirebaseSyncUiState.SignedOut -> {
                StatusCard(
                    message = stringResource(R.string.not_connected),
                    icon = Icons.Default.CloudOff,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    onClick = onSignInClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.sign_in_with_google))
                }
            }

            is FirebaseSyncUiState.Error -> {
                ErrorCard(message = uiState.message)

                Button(
                    onClick = onSignInClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.try_again))
                }
            }
        }
    }
}

@Composable
private fun SyncStatusIndicator(connectionState: SyncConnectionState) {
    val (icon, text, color) = when (connectionState) {
        SyncConnectionState.CONNECTED -> Triple(
            Icons.Default.CloudDone,
            stringResource(R.string.sync_connected),
            MaterialTheme.colorScheme.primary
        )
        SyncConnectionState.CONNECTING -> Triple(
            Icons.Default.CloudQueue,
            stringResource(R.string.sync_connecting),
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        SyncConnectionState.DISCONNECTED -> Triple(
            Icons.Default.CloudOff,
            stringResource(R.string.sync_offline),
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color
        )
    }
}
