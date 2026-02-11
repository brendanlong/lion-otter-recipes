package com.lionotter.recipes.ui.screens.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Sync
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
import com.lionotter.recipes.ui.components.ErrorCard
import com.lionotter.recipes.ui.components.ProgressCard
import com.lionotter.recipes.ui.components.StatusCard
import com.lionotter.recipes.ui.screens.firebase.FirebaseSyncUiState
import com.lionotter.recipes.ui.screens.firebase.SyncOperationState

@Composable
fun FirebaseSyncSection(
    uiState: FirebaseSyncUiState,
    syncEnabled: Boolean,
    lastSyncTimestamp: String?,
    operationState: SyncOperationState,
    onSignInClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onEnableSyncClick: () -> Unit,
    onDisableSyncClick: () -> Unit,
    onSyncNowClick: () -> Unit
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
                            // Last sync info
                            Text(
                                text = if (lastSyncTimestamp != null) {
                                    stringResource(R.string.last_synced, formatTimestamp(lastSyncTimestamp))
                                } else {
                                    stringResource(R.string.never_synced)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // Sync now button
                            val isSyncing = operationState is SyncOperationState.Syncing
                            OutlinedButton(
                                onClick = onSyncNowClick,
                                enabled = !isSyncing,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Text(
                                    if (isSyncing) stringResource(R.string.syncing)
                                    else stringResource(R.string.sync_now)
                                )
                            }
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

private fun formatTimestamp(timestamp: String): String {
    return try {
        val instant = java.time.Instant.parse(timestamp)
        val localDateTime = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
        val month = localDateTime.month.name.lowercase().replaceFirstChar { it.uppercase() }
        val day = localDateTime.dayOfMonth
        val year = localDateTime.year
        val hour = localDateTime.hour.toString().padStart(2, '0')
        val minute = localDateTime.minute.toString().padStart(2, '0')
        "$month $day, $year $hour:$minute"
    } catch (_: Exception) {
        timestamp
    }
}
