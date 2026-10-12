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
import coil3.compose.AsyncImage
import com.lionotter.recipes.data.remote.imageModel

/**
 * A recipe thumbnail that always reserves space for consistent layout alignment.
 * Shows a placeholder icon, with the recipe image drawn over it once it loads.
 *
 * Pass a null [contentDescription] when the recipe name is already shown next to the
 * thumbnail, so screen readers don't announce it twice.
 */
@Composable
fun RecipeThumbnail(
    imageUrl: String?,
    contentDescription: String?,
    size: Int,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Restaurant,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size((size / 2).dp)
        )
        if (imageUrl != null) {
            // AsyncImage (unlike SubcomposeAsyncImage) is cheap enough for lazy lists; it
            // draws nothing while loading or on error, leaving the placeholder visible.
            AsyncImage(
                model = imageModel(imageUrl),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}
