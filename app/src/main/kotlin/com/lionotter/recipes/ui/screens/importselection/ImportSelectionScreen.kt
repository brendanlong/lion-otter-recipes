package com.lionotter.recipes.ui.screens.importselection

import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lionotter.recipes.R
import com.lionotter.recipes.messages.resolve
import com.lionotter.recipes.ui.TestTags
import com.lionotter.recipes.ui.components.CollectEvents
import com.lionotter.recipes.ui.components.ErrorCard
import com.lionotter.recipes.ui.components.text
import com.lionotter.recipes.ui.components.RecipeTopAppBar

/**
 * Import selection destination. [ImportSelectionViewModel] reads its
 * [com.lionotter.recipes.ui.navigation.Route.ImportSelection] arguments from its SavedStateHandle and parses the file once, so selections survive rotation.
 */
@Composable
fun ImportSelectionRoute(
    onImportStarted: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ImportSelectionViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val selectionState by viewModel.uiState.collectAsStateWithLifecycle()

    // Events are collected here (not captured in a callback) so an import that finishes
    // starting after rotation navigates with the current NavController.
    CollectEvents(viewModel.events) { event ->
        when (event) {
            ImportSelectionEvent.ImportStarted -> onImportStarted()
            is ImportSelectionEvent.ShowMessage ->
                Toast.makeText(context, event.message.resolve(resources), Toast.LENGTH_LONG).show()
        }
    }

    // Auto-import single new .lorecipes recipes without showing selection screen
    LaunchedEffect(selectionState) {
        if (viewModel.shouldAutoImport()) {
            viewModel.importSelected()
        }
    }

    ImportSelectionScreen(
        title = stringResource(R.string.select_recipes_to_import),
        state = selectionState,
        onToggleItem = viewModel::toggleItem,
        onSelectAll = viewModel::selectAll,
        onDeselectAll = viewModel::deselectAll,
        onImportClick = viewModel::importSelected,
        onCancelClick = onCancel,
        modifier = modifier
    )
}

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
    onCancelClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
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
                        message = state.message.text(),
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
    val uniqueItems = remember(items) { items.distinctBy { it.id } }
    val selectedCount = uniqueItems.count { it.isSelected }
    val hasSelection = selectedCount > 0
    val allSelected = selectedCount == uniqueItems.size
    val selectAllState = when {
        allSelected -> ToggleableState.On
        hasSelection -> ToggleableState.Indeterminate
        else -> ToggleableState.Off
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Tri-state "select all" row; the count wraps below on narrow screens if needed
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .triStateToggleable(
                    state = selectAllState,
                    role = Role.Checkbox,
                    onClick = { if (allSelected) onDeselectAll() else onSelectAll() }
                )
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TriStateCheckbox(
                state = selectAllState,
                onClick = null,
                modifier = Modifier.padding(12.dp)
            )
            Text(
                text = stringResource(R.string.select_all),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f)
            )
            Text(
                text = stringResource(R.string.import_selection_count, selectedCount, uniqueItems.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HorizontalDivider()

        // Recipe checklist
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag(TestTags.IMPORT_SELECTION_LIST)
        ) {
            items(uniqueItems, key = { it.id }) { item ->
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
                modifier = Modifier
                    .weight(1f)
                    .testTag(TestTags.IMPORT_BUTTON),
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
            .testTag(TestTags.importItem(item.id))
            .toggleable(
                value = item.isSelected,
                role = Role.Checkbox,
                onValueChange = { onToggle() }
            )
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = item.isSelected,
            onCheckedChange = null,
            modifier = Modifier
                .padding(12.dp)
                .testTag(TestTags.importCheckbox(item.id))
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
