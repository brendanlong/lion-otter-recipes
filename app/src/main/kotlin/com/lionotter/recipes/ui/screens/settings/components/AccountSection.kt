package com.lionotter.recipes.ui.screens.settings.components

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lionotter.recipes.R
import com.lionotter.recipes.data.sync.AuthState
import com.lionotter.recipes.data.sync.SyncStatus
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.compose.auth.composeAuth
import io.github.jan.supabase.compose.auth.composable.NativeSignInResult
import io.github.jan.supabase.compose.auth.composable.rememberSignInWithGoogle

@Composable
fun AccountSection(
    authState: AuthState,
    syncStatus: SyncStatus,
    supabaseClient: SupabaseClient,
    onSignOut: () -> Unit,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.account),
            style = MaterialTheme.typography.titleMedium
        )

        if (authState.isSignedIn) {
            // Signed-in state
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (authState.avatarUrl != null) {
                        AsyncImage(
                            model = authState.avatarUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        authState.displayName?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        authState.email?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            // Sync status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when (syncStatus) {
                            SyncStatus.SYNCING -> Icons.Default.Sync
                            SyncStatus.UP_TO_DATE -> Icons.Default.CloudDone
                            SyncStatus.OFFLINE -> Icons.Default.CloudOff
                            SyncStatus.ERROR -> Icons.Default.Error
                            SyncStatus.DISABLED -> Icons.Default.Cloud
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = when (syncStatus) {
                            SyncStatus.ERROR -> MaterialTheme.colorScheme.error
                            SyncStatus.UP_TO_DATE -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (syncStatus) {
                            SyncStatus.DISABLED -> stringResource(R.string.sync_status_disabled)
                            SyncStatus.SYNCING -> stringResource(R.string.sync_status_syncing)
                            SyncStatus.UP_TO_DATE -> stringResource(R.string.sync_status_up_to_date)
                            SyncStatus.OFFLINE -> stringResource(R.string.sync_status_offline)
                            SyncStatus.ERROR -> stringResource(R.string.sync_status_error)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (syncStatus) {
                            SyncStatus.ERROR -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                TextButton(onClick = onSyncNow) {
                    Text(stringResource(R.string.sync_now))
                }
            }

            OutlinedButton(
                onClick = onSignOut,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.sign_out))
            }
        } else {
            // Signed-out state
            Text(
                text = stringResource(R.string.sign_in_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            GoogleSignInButton(
                supabaseClient = supabaseClient,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private const val TAG = "AccountSection"

@Composable
private fun GoogleSignInButton(
    supabaseClient: SupabaseClient,
    modifier: Modifier = Modifier
) {
    val composeAuth = supabaseClient.composeAuth
    val signInAction = composeAuth.rememberSignInWithGoogle(
        onResult = { result ->
            when (result) {
                is NativeSignInResult.Success -> {
                    // Auth state is handled reactively by AuthRepository
                }
                is NativeSignInResult.Error -> {
                    Log.w(TAG, "Google sign-in error: ${result.message}")
                }
                is NativeSignInResult.ClosedByUser -> {
                    // User cancelled — no action needed
                }
                is NativeSignInResult.NetworkError -> {
                    Log.w(TAG, "Google sign-in network error: ${result.message}")
                }
            }
        }
    )

    Button(
        onClick = { signInAction.startFlow() },
        modifier = modifier
    ) {
        Text(stringResource(R.string.sign_in_with_google))
    }
}
