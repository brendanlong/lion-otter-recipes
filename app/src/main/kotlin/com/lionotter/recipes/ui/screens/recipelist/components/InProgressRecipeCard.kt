package com.lionotter.recipes.ui.screens.recipelist.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lionotter.recipes.R
import com.lionotter.recipes.data.local.PendingImportEntity
import com.lionotter.recipes.ui.components.RecipeThumbnail
import com.lionotter.recipes.ui.components.SwipeActionBox
import com.lionotter.recipes.ui.components.SwipeActionBoxState
import com.lionotter.recipes.ui.components.rememberSwipeActionBoxState
import com.lionotter.recipes.ui.state.InProgressRecipe

@Composable
fun InProgressRecipeCard(
    inProgressRecipe: InProgressRecipe,
    onCancelRequest: () -> Unit,
    swipeState: SwipeActionBoxState = rememberSwipeActionBoxState(),
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null
) {
    val isFailed = inProgressRecipe.isFailed

    SwipeActionBox(
        state = swipeState,
        onEndToStartAction = onCancelRequest,
        enableStartToEnd = false,
        enableEndToStart = true,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.cancel_import),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (isFailed) 1f else 0.8f),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isFailed) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when {
                        inProgressRecipe.imageUrl != null -> {
                            RecipeThumbnail(
                                imageUrl = inProgressRecipe.imageUrl,
                                contentDescription = inProgressRecipe.name,
                                size = 80
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        isFailed -> {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        else -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(40.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = inProgressRecipe.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isFailed) {
                                inProgressRecipe.errorMessage ?: stringResource(R.string.import_failed)
                            } else {
                                statusText(inProgressRecipe.status)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isFailed) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = if (isFailed) 4 else 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Show spinner on right side when we have an image (since spinner isn't on left)
                    if (inProgressRecipe.imageUrl != null && !isFailed) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }

                if (isFailed && (onRetry != null || onDismiss != null)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        if (onDismiss != null) {
                            TextButton(onClick = onDismiss) {
                                Text(stringResource(R.string.dismiss_import))
                            }
                        }
                        if (onRetry != null) {
                            TextButton(onClick = onRetry) {
                                Text(stringResource(R.string.retry_import))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun statusText(status: String): String {
    return when (status) {
        PendingImportEntity.STATUS_PENDING -> stringResource(R.string.preparing_import)
        PendingImportEntity.STATUS_FETCHING_METADATA -> stringResource(R.string.fetching_recipe_page)
        PendingImportEntity.STATUS_METADATA_READY -> stringResource(R.string.analyzing_recipe)
        PendingImportEntity.STATUS_PARSING -> stringResource(R.string.analyzing_recipe)
        PendingImportEntity.STATUS_SUBMITTING_BATCH -> stringResource(R.string.submitting_batch)
        PendingImportEntity.STATUS_WAITING_FOR_BATCH -> stringResource(R.string.waiting_for_batch)
        PendingImportEntity.STATUS_SAVING -> stringResource(R.string.saving_recipe)
        PendingImportEntity.STATUS_FAILED -> stringResource(R.string.import_failed)
        else -> stringResource(R.string.importing)
    }
}
