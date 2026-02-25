package com.lionotter.recipes.ui.screens.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lionotter.recipes.R
import com.lionotter.recipes.data.remote.AuthState

@Composable
fun AccountSection(
    authState: AuthState,
    onSignInWithGoogle: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    isLinking: Boolean = false,
    isDeletingAccount: Boolean = false
) {
    var showSignOutDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteConfirmationText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = stringResource(R.string.account),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(8.dp))

        when (authState) {
            is AuthState.Anonymous -> {
                Text(
                    text = stringResource(R.string.guest_mode_description),
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (isLinking) {
                    CircularProgressIndicator()
                } else {
                    Button(onClick = onSignInWithGoogle) {
                        Text(stringResource(R.string.sign_in_with_google))
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.sign_in_sync_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            is AuthState.Google -> {
                Text(
                    text = stringResource(R.string.signed_in_as, authState.email),
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (isDeletingAccount) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.deleting_account),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Button(
                        onClick = { showSignOutDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.sign_out))
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            deleteConfirmationText = ""
                            showDeleteDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.delete_account))
                    }
                }
            }
            is AuthState.Loading -> {
                CircularProgressIndicator()
            }
        }
    }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = { Text(stringResource(R.string.sign_out)) },
            text = {
                Text(stringResource(R.string.sign_out_confirmation))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSignOutDialog = false
                        onSignOut()
                    }
                ) {
                    Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showDeleteDialog) {
        val isDeleteConfirmed = deleteConfirmationText.trim().lowercase() == "delete"
        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                deleteConfirmationText = ""
            },
            title = { Text(stringResource(R.string.delete_account_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.delete_account_warning))
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = deleteConfirmationText,
                        onValueChange = { deleteConfirmationText = it },
                        label = { Text(stringResource(R.string.delete_account_confirmation_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        deleteConfirmationText = ""
                        onDeleteAccount()
                    },
                    enabled = isDeleteConfirmed
                ) {
                    Text(
                        stringResource(R.string.delete),
                        color = if (isDeleteConfirmed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    deleteConfirmationText = ""
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
