package com.lionotter.recipes.ui.screens.grocerylist

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lionotter.recipes.R
import com.lionotter.recipes.messages.resolve
import com.lionotter.recipes.ui.components.CollectEvents
import com.lionotter.recipes.ui.format.formatScaleMultiplier
import com.lionotter.recipes.domain.model.MealType
import com.lionotter.recipes.ui.components.RecipeTopAppBar
import com.lionotter.recipes.ui.screens.mealplan.MealPlanDateFormat
import kotlinx.datetime.LocalDate

// The two steps still read the ViewModel directly; they get hoisted state when the grocery
// list state is reworked into a single derived UI state.
@Suppress("ComposeViewModelForwarding")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroceryListScreen(
    onBackClick: () -> Unit,
    viewModel: GroceryListViewModel = hiltViewModel()
) {
    val step by viewModel.step.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current

    CollectEvents(viewModel.events) { message ->
        snackbarHostState.showSnackbar(message.resolve(resources))
    }

    // The list is a sub-step of this screen, so system back returns to the selection
    BackHandler(enabled = step == GroceryListStep.VIEW_LIST) {
        viewModel.backToSelection()
    }

    when (step) {
        GroceryListStep.SELECT_RECIPES -> RecipeSelectionScreen(
            viewModel = viewModel,
            snackbarHostState = snackbarHostState,
            onBackClick = onBackClick
        )
        GroceryListStep.VIEW_LIST -> GroceryListDisplayScreen(
            viewModel = viewModel,
            snackbarHostState = snackbarHostState,
            onBackClick = { viewModel.backToSelection() }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecipeSelectionScreen(
    viewModel: GroceryListViewModel,
    snackbarHostState: SnackbarHostState,
    onBackClick: () -> Unit
) {
    val loadedEntries by viewModel.selectableEntries.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val selectableEntries = loadedEntries
    val hasSelection = selectableEntries.orEmpty().values.any { entries ->
        entries.any { it.isSelected }
    }

    Scaffold(
        topBar = {
            RecipeTopAppBar(
                title = stringResource(R.string.grocery_list),
                onBackClick = onBackClick
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!selectableEntries.isNullOrEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(16.dp)
                    ) {
                        Button(
                            onClick = { viewModel.generateGroceryList() },
                            enabled = hasSelection && !isGenerating,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isGenerating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(stringResource(R.string.generate_grocery_list))
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        if (selectableEntries == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (selectableEntries.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.no_meals_planned),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.grocery_list_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = paddingValues.calculateBottomPadding() + 8.dp
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Select all / Deselect all row
                item(key = "select_actions") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { viewModel.selectAll() }) {
                            Text(stringResource(R.string.select_all))
                        }
                        TextButton(onClick = { viewModel.deselectAll() }) {
                            Text(stringResource(R.string.deselect_all))
                        }
                    }
                }

                for ((date, entries) in selectableEntries) {
                    item(key = "date_header_$date") {
                        DateHeader(date = date)
                    }
                    items(
                        items = entries,
                        key = { it.entry.id }
                    ) { selectable ->
                        MealPlanCheckboxRow(
                            entry = selectable,
                            onToggle = { viewModel.toggleEntrySelection(selectable.entry.id) }
                        )
                    }
                    item(key = "date_spacer_$date") {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DateHeader(date: LocalDate) {
    Text(
        text = MealPlanDateFormat.dayHeader(date),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun MealPlanCheckboxRow(
    entry: SelectableMealPlanEntry,
    onToggle: () -> Unit
) {
    val mealTypeLabel = when (entry.entry.mealType) {
        MealType.BREAKFAST -> stringResource(R.string.breakfast)
        MealType.LUNCH -> stringResource(R.string.lunch)
        MealType.DINNER -> stringResource(R.string.dinner)
        MealType.SNACK -> stringResource(R.string.snack)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = entry.isSelected,
                role = Role.Checkbox,
                onValueChange = { onToggle() }
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = entry.isSelected,
            onCheckedChange = null,
            modifier = Modifier.padding(12.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.entry.recipeName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = mealTypeLabel + if (entry.entry.servings != 1.0) {
                    " • ${formatScaleMultiplier(entry.entry.servings)}"
                } else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroceryListDisplayScreen(
    viewModel: GroceryListViewModel,
    snackbarHostState: SnackbarHostState,
    onBackClick: () -> Unit
) {
    val items by viewModel.displayGroceryItems.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val skippedRecipeNames by viewModel.skippedRecipeNames.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val partialTotalSuffix = stringResource(R.string.grocery_total_partial_suffix)

    Scaffold(
        topBar = {
            RecipeTopAppBar(
                title = stringResource(R.string.grocery_list),
                onBackClick = onBackClick,
                actions = {
                    IconButton(
                        onClick = {
                            val shareText = viewModel.getShareText(partialTotalSuffix)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, shareText)
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                        },
                        enabled = !isGenerating && items.isNotEmpty()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = stringResource(R.string.share_grocery_list)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        if (isGenerating) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (items.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.no_ingredients),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (skippedRecipeNames.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    SkippedRecipesNotice(skippedRecipeNames)
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (skippedRecipeNames.isNotEmpty()) {
                    item(key = "skipped_notice") {
                        SkippedRecipesNotice(
                            names = skippedRecipeNames,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }
                items(
                    items = items,
                    key = { it.key }
                ) { item ->
                    GroceryItemRow(
                        item = item,
                        partialTotalSuffix = partialTotalSuffix,
                        onItemToggle = { viewModel.toggleItemChecked(item.key) },
                        onSourceToggle = { sourceKey -> viewModel.toggleSourceChecked(sourceKey) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SkippedRecipesNotice(names: List<String>, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    ) {
        Text(
            text = stringResource(R.string.grocery_list_skipped_recipes, names.joinToString(", ")),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
private fun GroceryItemRow(
    item: DisplayGroceryItem,
    partialTotalSuffix: String,
    onItemToggle: () -> Unit,
    onSourceToggle: (String) -> Unit
) {
    // If only one source total, show it directly
    if (item.sources.size <= 1) {
        val source = item.sources.firstOrNull() ?: return
        CheckRow(
            text = "${source.displayText} (${source.recipeName})",
            isChecked = item.isChecked || source.isChecked,
            onToggle = onItemToggle
        )
    } else {
        // Multiple sources - show aggregate header with sub-items
        Column {
            CheckRow(
                text = GroceryListViewModel.aggregateHeaderText(item, partialTotalSuffix),
                isChecked = item.isChecked,
                onToggle = onItemToggle,
                bold = true
            )
            for (source in item.sources) {
                CheckRow(
                    text = "${source.displayText} (${source.recipeName})",
                    isChecked = source.isChecked,
                    onToggle = { onSourceToggle(source.key) },
                    modifier = Modifier.padding(start = 40.dp),
                    small = true
                )
            }
        }
    }
}

@Composable
private fun CheckRow(
    text: String,
    isChecked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    small: Boolean = false
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = isChecked,
                role = Role.Checkbox,
                onValueChange = { onToggle() }
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isChecked,
            onCheckedChange = null,
            modifier = Modifier.padding(12.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else null,
            textDecoration = if (isChecked) TextDecoration.LineThrough else TextDecoration.None,
            color = if (isChecked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}
