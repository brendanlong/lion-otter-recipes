package com.lionotter.recipes.ui.screens.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.lionotter.recipes.ui.screens.googledrive.GoogleDriveUiState

@Composable
fun GoogleDriveSection(
    uiState: GoogleDriveUiState,
    onSignInClick: () -> Unit,
    onSignOutClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.google_drive),
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            text = stringResource(R.string.google_drive_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        when (uiState) {
            is GoogleDriveUiState.Loading -> {
                ProgressCard(message = stringResource(R.string.checking_sign_in_status))
            }

            is GoogleDriveUiState.SignedIn -> {
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
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Cloud,
                                    contentDescription = stringResource(R.string.connected_to_google_drive),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "  " + stringResource(R.string.connected_to_google_drive),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Text(
                                text = uiState.email,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                        OutlinedButton(onClick = onSignOutClick) {
                            Text(stringResource(R.string.disconnect))
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.google_drive_usage_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            is GoogleDriveUiState.SignedOut -> {
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
                    Text(stringResource(R.string.connect_to_google_drive))
                }
            }

            is GoogleDriveUiState.Error -> {
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
