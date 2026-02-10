package com.lionotter.recipes.ui.screens.grocerylist

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.model.MealType
import com.lionotter.recipes.ui.components.RecipeTopAppBar
import kotlinx.datetime.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroceryListScreen(
    onBackClick: () -> Unit,
    viewModel: GroceryListViewModel = hiltViewModel()
) {
    val step by viewModel.step.collectAsStateWithLifecycle()

    when (step) {
        GroceryListStep.SELECT_RECIPES -> RecipeSelectionScreen(
            viewModel = viewModel,
            onBackClick = onBackClick
        )
        GroceryListStep.VIEW_LIST -> GroceryListDisplayScreen(
            viewModel = viewModel,
            onBackClick = { viewModel.backToSelection() }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecipeSelectionScreen(
    viewModel: GroceryListViewModel,
    onBackClick: () -> Unit
) {
    val selectableEntries by viewModel.selectableEntries.collectAsStateWithLifecycle()
    val hasSelection = selectableEntries.values.any { entries ->
        entries.any { it.isSelected }
    }

    Scaffold(
        topBar = {
            RecipeTopAppBar(
                title = stringResource(R.string.grocery_list),
                onBackClick = onBackClick
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Button(
                    onClick = { viewModel.generateGroceryList() },
                    enabled = hasSelection,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.generate_grocery_list))
                }
            }
        }
    ) { paddingValues ->
        if (selectableEntries.isEmpty()) {
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
    val dayName = java.time.DayOfWeek.of(date.dayOfWeek.value)
        .getDisplayName(TextStyle.FULL, Locale.getDefault())
    val monthName = java.time.Month.of(date.monthNumber)
        .getDisplayName(TextStyle.SHORT, Locale.getDefault())

    Text(
        text = "$dayName, $monthName ${date.dayOfMonth}",
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
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = entry.isSelected,
            onCheckedChange = { onToggle() }
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
                    " \u2022 ${formatServings(entry.entry.servings)}x"
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
    onBackClick: () -> Unit
) {
    val items by viewModel.displayGroceryItems.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            RecipeTopAppBar(
                title = stringResource(R.string.grocery_list),
                onBackClick = onBackClick,
                actions = {
                    IconButton(onClick = {
                        val shareText = viewModel.getShareText()
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareText)
                        }
                        context.startActivity(Intent.createChooser(intent, null))
                    }) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = stringResource(R.string.share_grocery_list)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        if (items.isEmpty()) {
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
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(
                    items = items,
                    key = { it.key }
                ) { item ->
                    GroceryItemRow(
                        item = item,
                        onItemToggle = { viewModel.toggleItemChecked(item.key) },
                        onSourceToggle = { sourceKey -> viewModel.toggleSourceChecked(sourceKey) }
                    )
                }
            }
        }
    }
}

@Composable
private fun GroceryItemRow(
    item: DisplayGroceryItem,
    onItemToggle: () -> Unit,
    onSourceToggle: (String) -> Unit
) {
    val uncheckedSources = item.sources.filter { !it.isChecked }

    // If only one source total, show it directly
    if (item.sources.size <= 1) {
        val source = item.sources.firstOrNull() ?: return
        SingleIngredientRow(
            source = source,
            recipeName = source.recipeName,
            isChecked = item.isChecked || source.isChecked,
            onToggle = onItemToggle
        )
    } else if (uncheckedSources.size == 1 && !item.isChecked) {
        // Multiple sources but only one remaining unchecked - collapse to single item
        val source = uncheckedSources.first()
        SingleIngredientRow(
            source = source,
            recipeName = source.recipeName,
            isChecked = false,
            onToggle = { onSourceToggle(source.key) }
        )
    } else {
        // Multiple unchecked sources - show aggregate header with sub-items
        AnimatedVisibility(visible = !item.isChecked) {
            Column {
                AggregateIngredientHeader(
                    item = item,
                    onToggle = onItemToggle
                )
                // Sub-items
                for (source in item.sources) {
                    AnimatedVisibility(visible = !source.isChecked) {
                        SubIngredientRow(
                            source = source,
                            onToggle = { onSourceToggle(source.key) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SingleIngredientRow(
    source: DisplayGrocerySource,
    recipeName: String,
    isChecked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isChecked,
            onCheckedChange = { onToggle() }
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "${source.displayText} ($recipeName)",
            style = MaterialTheme.typography.bodyMedium,
            textDecoration = if (isChecked) TextDecoration.LineThrough else TextDecoration.None,
            color = if (isChecked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun AggregateIngredientHeader(
    item: DisplayGroceryItem,
    onToggle: () -> Unit
) {
    val headerText = if (item.totalAmount != null) {
        "${item.totalAmount} ${item.displayText}"
    } else {
        item.displayText
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = item.isChecked,
            onCheckedChange = { onToggle() }
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = headerText,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SubIngredientRow(
    source: DisplayGrocerySource,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 40.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = source.isChecked,
            onCheckedChange = { onToggle() }
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "${source.displayText} (${source.recipeName})",
            style = MaterialTheme.typography.bodySmall,
            textDecoration = if (source.isChecked) TextDecoration.LineThrough else TextDecoration.None,
            color = if (source.isChecked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun formatServings(servings: Double): String {
    return if (servings == servings.toLong().toDouble()) {
        servings.toLong().toString()
    } else {
        servings.toString()
    }
}
