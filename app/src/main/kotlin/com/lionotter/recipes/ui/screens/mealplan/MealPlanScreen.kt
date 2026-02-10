package com.lionotter.recipes.ui.screens.mealplan

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.MealType
import com.lionotter.recipes.ui.components.RecipeTopAppBar
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MealPlanScreen(
    onRecipeClick: (String) -> Unit,
    onBackClick: () -> Unit,
    viewModel: MealPlanViewModel = hiltViewModel()
) {
    val weekStart by viewModel.currentWeekStart.collectAsStateWithLifecycle()
    val weekMealPlans by viewModel.weekMealPlans.collectAsStateWithLifecycle()
    val showAddDialog by viewModel.showAddDialog.collectAsStateWithLifecycle()

    // State for delete confirmation
    var entryToDelete by remember { mutableStateOf<MealPlanEntry?>(null) }

    // Delete confirmation dialog
    entryToDelete?.let { entry ->
        MealPlanDeleteDialog(
            recipeName = entry.recipeName,
            onConfirm = {
                viewModel.deleteMealPlan(entry.id)
                entryToDelete = null
            },
            onDismiss = { entryToDelete = null }
        )
    }

    // Add meal plan dialog
    if (showAddDialog) {
        AddMealPlanDialog(
            viewModel = viewModel,
            onDismiss = { viewModel.dismissAddDialog() }
        )
    }

    Scaffold(
        topBar = {
            RecipeTopAppBar(
                title = stringResource(R.string.meal_planner),
                onBackClick = onBackClick,
                actions = {
                    IconButton(onClick = { viewModel.goToToday() }) {
                        Icon(
                            imageVector = Icons.Default.Today,
                            contentDescription = stringResource(R.string.today)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.openAddDialog() },
                containerColor = MaterialTheme.colorScheme.secondary
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.add_to_meal_plan)
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Week navigation header
            WeekNavigationHeader(
                weekStart = weekStart,
                onPreviousWeek = { viewModel.navigateWeek(false) },
                onNextWeek = { viewModel.navigateWeek(true) }
            )

            // Week content
            val days = (0..6).map { weekStart.plus(it, DateTimeUnit.DAY) }

            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (day in days) {
                    val meals = weekMealPlans[day]
                    item(key = "header_$day") {
                        DayHeader(date = day)
                    }
                    if (meals != null && meals.isNotEmpty()) {
                        items(
                            items = meals,
                            key = { it.id }
                        ) { entry ->
                            SwipeableMealPlanCard(
                                entry = entry,
                                onClick = { onRecipeClick(entry.recipeId) },
                                onEditRequest = { viewModel.openEditDialog(entry) },
                                onDeleteRequest = { entryToDelete = entry }
                            )
                        }
                    }
                    item(key = "spacer_$day") {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekNavigationHeader(
    weekStart: LocalDate,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit
) {
    val weekEnd = weekStart.plus(6, DateTimeUnit.DAY)
    val startMonth = java.time.Month.of(weekStart.monthNumber)
        .getDisplayName(TextStyle.SHORT, Locale.getDefault())
    val endMonth = java.time.Month.of(weekEnd.monthNumber)
        .getDisplayName(TextStyle.SHORT, Locale.getDefault())

    val dateRangeText = if (weekStart.monthNumber == weekEnd.monthNumber) {
        "$startMonth ${weekStart.dayOfMonth} - ${weekEnd.dayOfMonth}, ${weekStart.year}"
    } else if (weekStart.year == weekEnd.year) {
        "$startMonth ${weekStart.dayOfMonth} - $endMonth ${weekEnd.dayOfMonth}, ${weekStart.year}"
    } else {
        "$startMonth ${weekStart.dayOfMonth}, ${weekStart.year} - $endMonth ${weekEnd.dayOfMonth}, ${weekEnd.year}"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPreviousWeek) {
            Icon(
                imageVector = Icons.Default.ChevronLeft,
                contentDescription = stringResource(R.string.previous_week)
            )
        }
        Text(
            text = dateRangeText,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        IconButton(onClick = onNextWeek) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = stringResource(R.string.next_week)
            )
        }
    }
}

@Composable
private fun DayHeader(date: LocalDate) {
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SwipeableMealPlanCard(
    entry: MealPlanEntry,
    onClick: () -> Unit,
    onEditRequest: () -> Unit,
    onDeleteRequest: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> {
                    onDeleteRequest()
                    false
                }
                SwipeToDismissBoxValue.StartToEnd -> {
                    onEditRequest()
                    false
                }
                else -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val alignment = when (dismissState.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                else -> Alignment.CenterEnd
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentAlignment = alignment
            ) {
                when (dismissState.dismissDirection) {
                    SwipeToDismissBoxValue.StartToEnd -> Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(R.string.edit),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    else -> Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        enableDismissFromStartToEnd = true
    ) {
        MealPlanCard(
            entry = entry,
            onClick = onClick,
            onLongClick = { showMenu = true },
            showMenu = showMenu,
            onDismissMenu = { showMenu = false },
            onEditRequest = onEditRequest,
            onDeleteRequest = onDeleteRequest
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MealPlanCard(
    entry: MealPlanEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    showMenu: Boolean,
    onDismissMenu: () -> Unit,
    onEditRequest: () -> Unit,
    onDeleteRequest: () -> Unit
) {
    val mealTypeLabel = when (entry.mealType) {
        MealType.BREAKFAST -> stringResource(R.string.breakfast)
        MealType.LUNCH -> stringResource(R.string.lunch)
        MealType.DINNER -> stringResource(R.string.dinner)
        MealType.SNACK -> stringResource(R.string.snack)
    }

    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Recipe image
                if (entry.recipeImageUrl != null) {
                    AsyncImage(
                        model = entry.recipeImageUrl,
                        contentDescription = entry.recipeName,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(MaterialTheme.shapes.small),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.recipeName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = mealTypeLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (entry.servings != 1.0) {
                            Text(
                                text = stringResource(
                                    R.string.servings_format,
                                    formatServings(entry.servings)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Long-press context menu
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = onDismissMenu
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.edit)) },
                onClick = {
                    onDismissMenu()
                    onEditRequest()
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null
                    )
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.remove)) },
                onClick = {
                    onDismissMenu()
                    onDeleteRequest()
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            )
        }
    }
}

@Composable
private fun MealPlanDeleteDialog(
    recipeName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_meal_plan)) },
        text = { Text(stringResource(R.string.delete_meal_plan_confirmation, recipeName)) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.remove))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

private fun formatServings(servings: Double): String {
    return if (servings == servings.toLong().toDouble()) {
        servings.toLong().toString()
    } else {
        servings.toString()
    }
}
