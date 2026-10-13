package com.lionotter.recipes.ui.screens.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.model.TagBarSize
import kotlin.math.roundToInt

@Composable
fun TagsSection(
    tagBarSize: Int,
    onTagBarSizeChange: (Int) -> Unit,
    onManageTagsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.tags_section),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = stringResource(R.string.tags_section_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = onManageTagsClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = stringResource(R.string.manage_tags))
        }
        TagBarSizeSlider(tagBarSize = tagBarSize, onTagBarSizeChange = onTagBarSizeChange)
    }
}

@Composable
private fun TagBarSizeSlider(
    tagBarSize: Int,
    onTagBarSizeChange: (Int) -> Unit
) {
    // Follows the drag locally and saves once it ends
    var dragged by remember(tagBarSize) { mutableFloatStateOf(tagBarSize.toFloat()) }
    val shown = dragged.roundToInt()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.tag_bar_size),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = shown.toString(),
                style = MaterialTheme.typography.bodyLarge
            )
        }
        Text(
            text = stringResource(R.string.tag_bar_size_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = dragged,
            onValueChange = { dragged = it },
            onValueChangeFinished = {
                val size = dragged.roundToInt()
                if (size != tagBarSize) onTagBarSizeChange(size)
            },
            valueRange = TagBarSize.MIN.toFloat()..TagBarSize.MAX.toFloat(),
            steps = TagBarSize.MAX - TagBarSize.MIN - 1
        )
    }
}
