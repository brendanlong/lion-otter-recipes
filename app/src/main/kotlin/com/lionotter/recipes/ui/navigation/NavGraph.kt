package com.lionotter.recipes.ui.navigation

import android.net.Uri
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
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
import com.lionotter.recipes.ui.screens.mealplan.MealPlanScreen
import com.lionotter.recipes.ui.screens.recipedetail.RecipeDetailScreen
import com.lionotter.recipes.ui.screens.recipelist.RecipeListScreen
import com.lionotter.recipes.ui.screens.recipelist.RecipeListViewModel
import com.lionotter.recipes.ui.screens.settings.SettingsScreen

sealed class Screen(val route: String) {
    object RecipeList : Screen("recipes")
    object RecipeDetail : Screen("recipes/{recipeId}") {
        fun createRoute(recipeId: String) = "recipes/$recipeId"
    }
    object EditRecipe : Screen("recipes/{recipeId}/edit") {
        fun createRoute(recipeId: String) = "recipes/$recipeId/edit"
    }
    object AddRecipe : Screen("add-recipe?url={url}") {
        fun createRoute(url: String? = null) =
            if (url != null) "add-recipe?url=${Uri.encode(url)}"
            else "add-recipe"
    }
    object Settings : Screen("settings")
    object MealPlan : Screen("meal-plan")
    object GroceryList : Screen("grocery-list/{weekStart}") {
        fun createRoute(weekStart: String) = "grocery-list/$weekStart"
    }
    object ImportDebugList : Screen("import-debug")
    object ImportDebugDetail : Screen("import-debug/{debugEntryId}") {
        fun createRoute(debugEntryId: String) = "import-debug/$debugEntryId"
    }
    object ImportSelection : Screen("import-selection/{importType}?uri={uri}") {
        fun createRoute(importType: String, uri: String) =
            "import-selection/$importType?uri=${Uri.encode(uri)}"
    }
}

private const val TAG = "NavGraph"

private fun NavBackStackEntry.isResumed() = lifecycle.currentState == Lifecycle.State.RESUMED

/**
 * Navigates only if [from] is the resumed (visible, interactive) destination. Drops repeated
 * taps that arrive while a transition is already running, which would otherwise push the
 * same screen twice.
 */
private fun NavController.navigateFrom(
    from: NavBackStackEntry,
    route: String,
    builder: NavOptionsBuilder.() -> Unit = {}
) {
    if (from.isResumed()) navigate(route, builder)
}

private fun NavController.openRecipe(recipeId: String) {
    val current = currentBackStackEntry
    if (current?.destination?.route == Screen.RecipeDetail.route &&
        current.arguments?.getString("recipeId") == recipeId
    ) {
        return
    }
    navigate(Screen.RecipeDetail.createRoute(recipeId))
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
                    navController.navigate(Screen.AddRecipe.createRoute(event.url))
                }
                is AppIntentEvent.ImportFile -> {
                    requestNotificationPermission()
                    navController.navigate(
                        Screen.ImportSelection.createRoute("lorecipes", event.uri.toString())
                    )
                }
                is AppIntentEvent.OpenRecipe -> navController.openRecipe(event.recipeId)
                AppIntentEvent.NoUrlInSharedText ->
                    Toast.makeText(context, noLinkMessage, Toast.LENGTH_LONG).show()
            }
        }
    }

    // Navigate back, falling back to RecipeList if the back stack is empty.
    val navigateBack: () -> Unit = {
        if (!navController.popBackStack()) {
            navController.navigate(Screen.RecipeList.route) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    fun NavBackStackEntry.back() {
        if (isResumed()) navigateBack()
    }

    NavHost(
        navController = navController,
        startDestination = Screen.RecipeList.route
    ) {
        composable(Screen.RecipeList.route) { entry ->
            RecipeListScreen(
                onRecipeClick = { recipeId ->
                    navController.navigateFrom(entry, Screen.RecipeDetail.createRoute(recipeId))
                },
                onAddRecipeClick = {
                    navController.navigateFrom(entry, Screen.AddRecipe.createRoute())
                },
                onSettingsClick = {
                    navController.navigateFrom(entry, Screen.Settings.route)
                },
                onMealPlanClick = {
                    navController.navigateFrom(entry, Screen.MealPlan.route)
                }
            )
        }

        composable(
            route = Screen.RecipeDetail.route,
            arguments = listOf(
                navArgument("recipeId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val recipeIdArg = backStackEntry.arguments?.getString("recipeId") ?: ""
            val editSuccess = backStackEntry.savedStateHandle
                .get<Boolean>("edit_success") == true
            RecipeDetailScreen(
                onBackClick = { backStackEntry.back() },
                onEditClick = {
                    navController.navigateFrom(backStackEntry, Screen.EditRecipe.createRoute(recipeIdArg))
                },
                onNavigateToSettings = {
                    navController.navigateFrom(backStackEntry, Screen.Settings.route)
                },
                onTagClick = { tag ->
                    if (backStackEntry.isResumed()) {
                        try {
                            val listEntry = navController.getBackStackEntry(Screen.RecipeList.route)
                            listEntry.savedStateHandle[RecipeListViewModel.KEY_SEARCH_QUERY] = ""
                            listEntry.savedStateHandle[RecipeListViewModel.KEY_SELECTED_TAG] = tag
                            navController.popBackStack(Screen.RecipeList.route, inclusive = false)
                        } catch (e: IllegalArgumentException) {
                            Log.w(TAG, "Recipe list not on back stack; can't filter by tag", e)
                        }
                    }
                },
                editSuccess = editSuccess,
                onEditSuccessConsumed = {
                    backStackEntry.savedStateHandle.remove<Boolean>("edit_success")
                }
            )
        }

        composable(
            route = Screen.EditRecipe.route,
            arguments = listOf(
                navArgument("recipeId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            EditRecipeScreen(
                onBackClick = { backStackEntry.back() },
                onEditSuccess = {
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("edit_success", true)
                    navigateBack()
                },
                onCopySuccess = { newRecipeId ->
                    // Navigate to the new recipe, clearing the edit screen from the back stack
                    navController.navigate(Screen.RecipeDetail.createRoute(newRecipeId)) {
                        popUpTo(Screen.RecipeDetail.route) { inclusive = false }
                    }
                }
            )
        }

        composable(
            route = Screen.AddRecipe.route,
            arguments = listOf(
                navArgument("url") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            AddRecipeScreen(
                onBackClick = { backStackEntry.back() },
                onRecipeAdded = { recipeId ->
                    // Clear back stack and set up proper navigation: RecipeList -> RecipeDetail
                    // This ensures back from RecipeDetail always goes to RecipeList
                    navController.navigate(Screen.RecipeList.route) {
                        popUpTo(0) { inclusive = true }
                    }
                    navController.navigate(Screen.RecipeDetail.createRoute(recipeId))
                },
                onNavigateToSettings = {
                    navController.navigateFrom(backStackEntry, Screen.Settings.route)
                },
                onNavigateToImportSelection = { importType, uri ->
                    navController.navigate(
                        Screen.ImportSelection.createRoute(importType, uri.toString())
                    )
                }
            )
        }

        composable(Screen.MealPlan.route) { entry ->
            MealPlanScreen(
                onRecipeClick = { recipeId ->
                    navController.navigateFrom(entry, Screen.RecipeDetail.createRoute(recipeId))
                },
                onBackClick = { entry.back() },
                onGroceryListClick = { weekStart ->
                    navController.navigateFrom(entry, Screen.GroceryList.createRoute(weekStart))
                }
            )
        }

        composable(
            route = Screen.GroceryList.route,
            arguments = listOf(
                navArgument("weekStart") { type = NavType.StringType }
            )
        ) { entry ->
            GroceryListScreen(
                onBackClick = { entry.back() }
            )
        }

        composable(Screen.Settings.route) { entry ->
            SettingsScreen(
                onBackClick = { entry.back() },
                onNavigateToImportDebug = {
                    navController.navigateFrom(entry, Screen.ImportDebugList.route)
                },
                onNavigateToImportSelection = { importType, uri ->
                    navController.navigate(
                        Screen.ImportSelection.createRoute(importType, uri.toString())
                    )
                }
            )
        }

        composable(Screen.ImportDebugList.route) { entry ->
            ImportDebugListScreen(
                onBackClick = { entry.back() },
                onEntryClick = { debugEntryId ->
                    navController.navigateFrom(entry, Screen.ImportDebugDetail.createRoute(debugEntryId))
                }
            )
        }

        composable(
            route = Screen.ImportDebugDetail.route,
            arguments = listOf(
                navArgument("debugEntryId") { type = NavType.StringType }
            )
        ) { entry ->
            ImportDebugDetailScreen(
                onBackClick = { entry.back() },
                onNavigateToRecipe = { recipeId ->
                    navController.navigateFrom(entry, Screen.RecipeDetail.createRoute(recipeId))
                }
            )
        }

        composable(
            route = Screen.ImportSelection.route,
            arguments = listOf(
                navArgument("importType") { type = NavType.StringType },
                navArgument("uri") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            ImportSelectionRoute(
                onImportStarted = {
                    // All import types: navigate to RecipeList immediately
                    navController.navigate(Screen.RecipeList.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onCancel = { backStackEntry.back() }
            )
        }
    }
}
