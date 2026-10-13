package com.lionotter.recipes.ui.screens.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lionotter.recipes.R

@Composable
fun TagsSection(
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
    }
}
