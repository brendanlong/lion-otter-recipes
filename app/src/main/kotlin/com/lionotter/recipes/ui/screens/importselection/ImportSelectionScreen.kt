package com.lionotter.recipes.ui.screens.importselection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lionotter.recipes.R
import com.lionotter.recipes.ui.components.ErrorCard
import com.lionotter.recipes.ui.components.RecipeTopAppBar

/**
 * Shared import selection screen that displays a checklist of recipes
 * found in an import file. Used by Paprika import, .lorecipes file import,
 * and ZIP backup import.
 *
 * @param title The title to display in the top bar
 * @param state The current UI state
 * @param onToggleItem Called when a recipe checkbox is toggled
 * @param onSelectAll Called when "Select All" is tapped
 * @param onDeselectAll Called when "Deselect All" is tapped
 * @param onImportClick Called when the Import button is tapped
 * @param onCancelClick Called when Cancel is tapped
 */
@Composable
fun ImportSelectionScreen(
    title: String,
    state: ImportSelectionUiState,
    onToggleItem: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onImportClick: () -> Unit,
    onCancelClick: () -> Unit
) {
    Scaffold(
        topBar = {
            RecipeTopAppBar(
                title = title,
                onBackClick = onCancelClick
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (state) {
                is ImportSelectionUiState.Loading -> {
                    LoadingContent()
                }
                is ImportSelectionUiState.Ready -> {
                    SelectionContent(
                        items = state.items,
                        onToggleItem = onToggleItem,
                        onSelectAll = onSelectAll,
                        onDeselectAll = onDeselectAll,
                        onImportClick = onImportClick,
                        onCancelClick = onCancelClick
                    )
                }
                is ImportSelectionUiState.Error -> {
                    ErrorContent(
                        message = state.message,
                        onCancelClick = onCancelClick
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.reading_recipes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SelectionContent(
    items: List<ImportSelectionItem>,
    onToggleItem: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onImportClick: () -> Unit,
    onCancelClick: () -> Unit
) {
    val selectedCount = items.count { it.isSelected }
    val hasSelection = selectedCount > 0

    Column(modifier = Modifier.fillMaxSize()) {
        // Select All / Deselect All row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.import_selection_count, selectedCount, items.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSelectAll) {
                    Text(stringResource(R.string.select_all))
                }
                OutlinedButton(onClick = onDeselectAll) {
                    Text(stringResource(R.string.deselect_all))
                }
            }
        }

        // Recipe checklist
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            items(items, key = { it.id }) { item ->
                RecipeChecklistItem(
                    item = item,
                    onToggle = { onToggleItem(item.id) }
                )
            }
        }

        // Bottom buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onCancelClick,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.cancel))
            }
            Button(
                onClick = onImportClick,
                modifier = Modifier.weight(1f),
                enabled = hasSelection
            ) {
                Text(stringResource(R.string.import_selected, selectedCount))
            }
        }
    }
}

@Composable
private fun RecipeChecklistItem(
    item: ImportSelectionItem,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = item.isSelected,
            onCheckedChange = { onToggle() }
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge
            )
            if (item.alreadyExists) {
                Text(
                    text = stringResource(R.string.recipe_already_exists),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onCancelClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        ErrorCard(message = message)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onCancelClick) {
            Text(stringResource(R.string.back))
        }
    }
}
