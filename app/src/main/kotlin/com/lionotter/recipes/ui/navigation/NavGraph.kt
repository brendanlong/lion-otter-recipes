package com.lionotter.recipes.ui.navigation

import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.lionotter.recipes.AppIntentEvent
import com.lionotter.recipes.R
import com.lionotter.recipes.SharedIntentViewModel
import com.lionotter.recipes.ui.components.rememberNotificationPermissionRequester
import com.lionotter.recipes.ui.screens.addrecipe.AddRecipeScreen
import com.lionotter.recipes.ui.screens.editrecipe.EditRecipeScreen
import com.lionotter.recipes.ui.screens.grocerylist.GroceryListScreen
import com.lionotter.recipes.ui.screens.importdebug.ImportDebugDetailScreen
import com.lionotter.recipes.ui.screens.importdebug.ImportDebugListScreen
import com.lionotter.recipes.ui.screens.importselection.ImportSelectionRoute
import com.lionotter.recipes.ui.screens.importselection.ImportType
import com.lionotter.recipes.ui.screens.mealplan.MealPlanScreen
import com.lionotter.recipes.ui.screens.recipedetail.RecipeDetailScreen
import com.lionotter.recipes.ui.screens.recipelist.RecipeListScreen
import com.lionotter.recipes.ui.screens.settings.SettingsScreen

private const val TAG = "NavGraph"

private fun NavBackStackEntry.isResumed() = lifecycle.currentState == Lifecycle.State.RESUMED

/**
 * Navigates only if [from] is the resumed (visible, interactive) destination. Drops repeated
 * taps that arrive while a transition is already running, which would otherwise push the
 * same screen twice.
 */
private fun NavController.navigateFrom(
    from: NavBackStackEntry,
    route: Route,
    builder: NavOptionsBuilder.() -> Unit = {}
) {
    if (from.isResumed()) navigate(route, builder)
}

private fun NavController.openRecipe(recipeId: String) {
    val current = currentBackStackEntry
    if (current != null &&
        current.destination.hasRoute<Route.RecipeDetail>() &&
        current.toRoute<Route.RecipeDetail>().recipeId == recipeId
    ) {
        return
    }
    navigate(Route.RecipeDetail(recipeId))
}

/** Replaces the whole back stack with the recipe list. */
private fun NavController.resetToRecipeList() {
    navigate(Route.RecipeList) {
        popUpTo(graph.id) { inclusive = true }
    }
}

@Composable
fun NavGraph(
    sharedIntentViewModel: SharedIntentViewModel? = null
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val requestNotificationPermission by rememberUpdatedState(rememberNotificationPermissionRequester())
    val noLinkMessage = stringResource(R.string.no_link_in_shared_text)

    // Shares, file opens and notification taps. Events are buffered in the ViewModel until
    // collected here and consumed exactly once, so they aren't lost while auth is loading
    // and aren't replayed on rotation.
    LaunchedEffect(sharedIntentViewModel) {
        sharedIntentViewModel?.events?.collect { event ->
            when (event) {
                is AppIntentEvent.ImportUrl -> {
                    requestNotificationPermission()
                    navController.navigate(Route.AddRecipe(event.url))
                }
                is AppIntentEvent.ImportFile -> {
                    requestNotificationPermission()
                    val uri = event.uri.toString()
                    navController.navigate(Route.ImportSelection(ImportType.guessFromUri(uri), uri))
                }
                is AppIntentEvent.OpenRecipe -> navController.openRecipe(event.recipeId)
                AppIntentEvent.NoUrlInSharedText ->
                    Toast.makeText(context, noLinkMessage, Toast.LENGTH_LONG).show()
            }
        }
    }

    // Navigate back, falling back to RecipeList if the back stack is empty.
    val navigateBack: () -> Unit = {
        if (!navController.popBackStack()) navController.resetToRecipeList()
    }

    fun NavBackStackEntry.back() {
        if (isResumed()) navigateBack()
    }

    NavHost(
        navController = navController,
        startDestination = Route.RecipeList
    ) {
        composable<Route.RecipeList> { entry ->
            val filterTag by entry.resultState(NavResult.FilterByTag)
            RecipeListScreen(
                onRecipeClick = { recipeId ->
                    navController.navigateFrom(entry, Route.RecipeDetail(recipeId))
                },
                onAddRecipeClick = {
                    navController.navigateFrom(entry, Route.AddRecipe())
                },
                onSettingsClick = {
                    navController.navigateFrom(entry, Route.Settings)
                },
                onMealPlanClick = {
                    navController.navigateFrom(entry, Route.MealPlan)
                },
                filterTag = filterTag,
                onFilterTagConsumed = { entry.consumeResult(NavResult.FilterByTag) }
            )
        }

        composable<Route.RecipeDetail> { backStackEntry ->
            val recipeId = backStackEntry.toRoute<Route.RecipeDetail>().recipeId
            val editSaved by backStackEntry.resultState(NavResult.EditSaved)
            RecipeDetailScreen(
                onBackClick = { backStackEntry.back() },
                onEditClick = {
                    navController.navigateFrom(backStackEntry, Route.EditRecipe(recipeId))
                },
                onNavigateToSettings = {
                    navController.navigateFrom(backStackEntry, Route.Settings)
                },
                onTagClick = { tag ->
                    if (backStackEntry.isResumed()) {
                        try {
                            val listEntry = navController.getBackStackEntry<Route.RecipeList>()
                            listEntry.setResult(NavResult.FilterByTag, tag)
                            navController.popBackStack<Route.RecipeList>(inclusive = false)
                        } catch (e: IllegalArgumentException) {
                            Log.w(TAG, "Recipe list not on back stack; can't filter by tag", e)
                        }
                    }
                },
                editSuccess = editSaved == true,
                onEditSuccessConsumed = { backStackEntry.consumeResult(NavResult.EditSaved) }
            )
        }

        composable<Route.EditRecipe> { backStackEntry ->
            EditRecipeScreen(
                onBackClick = { backStackEntry.back() },
                onEditSuccess = {
                    navController.setPreviousResult(NavResult.EditSaved, true)
                    navigateBack()
                },
                onCopySuccess = { newRecipeId ->
                    // Navigate to the new recipe, clearing the edit screen from the back stack
                    navController.navigate(Route.RecipeDetail(newRecipeId)) {
                        popUpTo<Route.RecipeDetail> { inclusive = false }
                    }
                }
            )
        }

        composable<Route.AddRecipe> { backStackEntry ->
            AddRecipeScreen(
                onBackClick = { backStackEntry.back() },
                onRecipeAdded = { recipeId ->
                    // Clear back stack and set up proper navigation: RecipeList -> RecipeDetail
                    // This ensures back from RecipeDetail always goes to RecipeList
                    navController.resetToRecipeList()
                    if (recipeId != null) {
                        navController.navigate(Route.RecipeDetail(recipeId))
                    }
                },
                onNavigateToSettings = {
                    navController.navigateFrom(backStackEntry, Route.Settings)
                },
                onNavigateToImportSelection = { importType, uri ->
                    navController.navigate(Route.ImportSelection(importType, uri.toString()))
                }
            )
        }

        composable<Route.MealPlan> { entry ->
            MealPlanScreen(
                onRecipeClick = { recipeId ->
                    navController.navigateFrom(entry, Route.RecipeDetail(recipeId))
                },
                onBackClick = { entry.back() },
                onGroceryListClick = { weekStart ->
                    navController.navigateFrom(entry, Route.GroceryList(weekStart))
                }
            )
        }

        composable<Route.GroceryList> { entry ->
            GroceryListScreen(
                onBackClick = { entry.back() }
            )
        }

        composable<Route.Settings> { entry ->
            SettingsScreen(
                onBackClick = { entry.back() },
                onNavigateToImportDebug = {
                    navController.navigateFrom(entry, Route.ImportDebugList)
                },
                onNavigateToImportSelection = { importType, uri ->
                    navController.navigate(Route.ImportSelection(importType, uri.toString()))
                }
            )
        }

        composable<Route.ImportDebugList> { entry ->
            ImportDebugListScreen(
                onBackClick = { entry.back() },
                onEntryClick = { debugEntryId ->
                    navController.navigateFrom(entry, Route.ImportDebugDetail(debugEntryId))
                }
            )
        }

        composable<Route.ImportDebugDetail> { entry ->
            ImportDebugDetailScreen(
                onBackClick = { entry.back() },
                onNavigateToRecipe = { recipeId ->
                    navController.navigateFrom(entry, Route.RecipeDetail(recipeId))
                }
            )
        }

        composable<Route.ImportSelection> { backStackEntry ->
            ImportSelectionRoute(
                onImportStarted = {
                    // All import types: navigate to RecipeList immediately
                    navController.resetToRecipeList()
                },
                onCancel = { backStackEntry.back() }
            )
        }
    }
}
