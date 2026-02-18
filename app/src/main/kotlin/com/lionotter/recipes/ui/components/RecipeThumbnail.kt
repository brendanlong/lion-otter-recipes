package com.lionotter.recipes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage

/**
 * A recipe thumbnail that always reserves space for consistent layout alignment.
 * Shows the recipe image if available and loadable, otherwise shows a placeholder icon.
 */
@Composable
fun RecipeThumbnail(
    imageUrl: String?,
    contentDescription: String,
    size: Int,
    modifier: Modifier = Modifier
) {
    val placeholderContent: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Restaurant,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size((size / 2).dp)
            )
        }
    }

    if (imageUrl != null) {
        SubcomposeAsyncImage(
            model = imageUrl,
            contentDescription = contentDescription,
            modifier = modifier
                .size(size.dp)
                .clip(MaterialTheme.shapes.small),
            contentScale = ContentScale.Crop,
            loading = { placeholderContent() },
            error = { placeholderContent() }
        )
    } else {
        Box(
            modifier = modifier
                .size(size.dp)
                .clip(MaterialTheme.shapes.small)
        ) {
            placeholderContent()
        }
    }
}
