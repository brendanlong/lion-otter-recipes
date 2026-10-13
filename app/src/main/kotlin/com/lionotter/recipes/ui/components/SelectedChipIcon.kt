package com.lionotter.recipes.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** The check on a selected filter chip, so the selection shows without color (e-ink, grayscale). */
@Composable
fun SelectedChipIcon(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Default.Check,
        contentDescription = null,
        modifier = modifier.size(FilterChipDefaults.IconSize)
    )
}
