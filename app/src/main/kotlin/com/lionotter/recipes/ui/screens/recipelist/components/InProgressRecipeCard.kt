package com.lionotter.recipes.ui.screens.recipelist.components

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import com.lionotter.recipes.ui.state.InProgressRecipe

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InProgressRecipeCard(
    inProgressRecipe: InProgressRecipe,
    onCancelRequest: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onCancelRequest()
                false
            } else {
                false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
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
        },
        enableDismissFromStartToEnd = false
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(0.8f),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Image or loading indicator
                if (inProgressRecipe.imageUrl != null) {
                    RecipeThumbnail(
                        imageUrl = inProgressRecipe.imageUrl,
                        contentDescription = inProgressRecipe.name,
                        size = 80
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(40.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
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
                        text = statusText(inProgressRecipe.status),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Show spinner on right side when we have an image (since spinner isn't on left)
                if (inProgressRecipe.imageUrl != null) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(24.dp),
                        strokeWidth = 2.dp
                    )
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
        PendingImportEntity.STATUS_SAVING -> stringResource(R.string.saving_recipe)
        PendingImportEntity.STATUS_FAILED -> stringResource(R.string.import_failed)
        else -> stringResource(R.string.importing)
    }
}
