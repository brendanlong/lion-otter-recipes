package com.lionotter.recipes.ui.screens.recipelist.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lionotter.recipes.ui.theme.LocalEinkTheme
import com.lionotter.recipes.ui.theme.einkBorder

@Composable
internal fun TagChip(tag: String) {
    // The e-ink theme's secondary container is black, for selected things; a tag is outlined.
    val eink = LocalEinkTheme.current
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (eink) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (eink) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSecondaryContainer
        ),
        border = einkBorder(),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = tag,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
