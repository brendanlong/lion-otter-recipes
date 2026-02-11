package com.lionotter.recipes.ui.navigation

import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.core.net.toUri
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lionotter.recipes.R
import com.lionotter.recipes.SharedIntentViewModel
import com.lionotter.recipes.ui.screens.addrecipe.AddRecipeScreen
import com.lionotter.recipes.ui.screens.importdebug.ImportDebugDetailScreen
import com.lionotter.recipes.ui.screens.importdebug.ImportDebugListScreen
import com.lionotter.recipes.ui.screens.synclog.SyncLogScreen
import com.lionotter.recipes.ui.screens.grocerylist.GroceryListScreen
import com.lionotter.recipes.ui.screens.importselection.ImportSelectionScreen
import com.lionotter.recipes.ui.screens.importselection.ImportSelectionViewModel
import com.lionotter.recipes.ui.screens.mealplan.MealPlanScreen
import com.lionotter.recipes.ui.screens.recipedetail.RecipeDetailScreen
import com.lionotter.recipes.ui.screens.recipelist.RecipeListScreen
import com.lionotter.recipes.ui.screens.settings.SettingsScreen
import kotlinx.coroutines.flow.collectLatest

sealed class Screen(val route: String) {
    object RecipeList : Screen("recipes")
    object RecipeDetail : Screen("recipes/{recipeId}") {
        fun createRoute(recipeId: String) = "recipes/$recipeId"
    }
    object AddRecipe : Screen("add-recipe?url={url}") {
        fun createRoute(url: String? = null) =
            if (url != null) "add-recipe?url=${url.replace("/", "%2F").replace(":", "%3A").replace("?", "%3F").replace("&", "%26").replace("=", "%3D")}"
            else "add-recipe"
    }
    object Settings : Screen("settings")
    object MealPlan : Screen("meal-plan")
    object GroceryList : Screen("grocery-list/{weekStart}") {
        fun createRoute(weekStart: String) = "grocery-list/$weekStart"
    }
    object SyncLogs : Screen("sync-logs")
    object ImportDebugList : Screen("import-debug")
    object ImportDebugDetail : Screen("import-debug/{debugEntryId}") {
        fun createRoute(debugEntryId: String) = "import-debug/$debugEntryId"
    }
    object ImportSelection : Screen("import-selection/{importType}?uri={uri}") {
        fun createRoute(importType: String, uri: String) =
            "import-selection/$importType?uri=${Uri.encode(uri)}"
    }
}

@Composable
fun NavGraph(
    sharedIntentViewModel: SharedIntentViewModel? = null,
    initialSharedUrl: String? = null,
    initialFileUri: Uri? = null,
    recipeId: String? = null
) {
    val navController = rememberNavController()
    val context = LocalContext.current

    val startDestination = when {
        recipeId != null -> Screen.RecipeDetail.createRoute(recipeId)
        initialSharedUrl != null -> Screen.AddRecipe.createRoute(initialSharedUrl)
        else -> Screen.RecipeList.route
    }

    // Handle shared URL changes while app is running
    LaunchedEffect(sharedIntentViewModel) {
        sharedIntentViewModel?.sharedUrl?.collectLatest { url ->
            if (url != null) {
                navController.navigate(Screen.AddRecipe.createRoute(url))
            }
        }
    }

    // Handle .lorecipes file import on launch - navigate to selection screen
    LaunchedEffect(initialFileUri) {
        if (initialFileUri != null) {
            navController.navigate(
                Screen.ImportSelection.createRoute("lorecipes", initialFileUri.toString())
            )
        }
    }

    // Handle .lorecipes file shared/opened while app is running
    LaunchedEffect(sharedIntentViewModel) {
        sharedIntentViewModel?.sharedFileUri?.collectLatest { uri ->
            navController.navigate(
                Screen.ImportSelection.createRoute("lorecipes", uri.toString())
            )
        }
    }

    // Helper to navigate back, falling back to RecipeList if back stack is empty.
    // This handles the case when the app is launched via share intent (AddRecipe is
    // the start destination) or after importing a recipe from share intent flow.
    val navigateBack: () -> Unit = {
        if (!navController.popBackStack()) {
            navController.navigate(Screen.RecipeList.route) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.RecipeList.route) {
            RecipeListScreen(
                onRecipeClick = { recipeId ->
                    navController.navigate(Screen.RecipeDetail.createRoute(recipeId))
                },
                onAddRecipeClick = {
                    navController.navigate(Screen.AddRecipe.createRoute())
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                },
                onMealPlanClick = {
                    navController.navigate(Screen.MealPlan.route)
                }
            )
        }

        composable(
            route = Screen.RecipeDetail.route,
            arguments = listOf(
                navArgument("recipeId") { type = NavType.StringType }
            )
        ) {
            RecipeDetailScreen(
                onBackClick = navigateBack
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
            val urlArg = backStackEntry.arguments?.getString("url")
            AddRecipeScreen(
                sharedUrl = urlArg,
                onBackClick = navigateBack,
                onRecipeAdded = { recipeId ->
                    // Clear back stack and set up proper navigation: RecipeList -> RecipeDetail
                    // This ensures back from RecipeDetail always goes to RecipeList
                    navController.navigate(Screen.RecipeList.route) {
                        popUpTo(0) { inclusive = true }
                    }
                    navController.navigate(Screen.RecipeDetail.createRoute(recipeId))
                },
                onPaprikaImportComplete = {
                    // Navigate back to recipe list after Paprika batch import
                    navController.navigate(Screen.RecipeList.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                },
                onNavigateToImportSelection = { importType, uri ->
                    navController.navigate(
                        Screen.ImportSelection.createRoute(importType, uri.toString())
                    )
                }
            )
        }

        composable(Screen.MealPlan.route) {
            MealPlanScreen(
                onRecipeClick = { recipeId ->
                    navController.navigate(Screen.RecipeDetail.createRoute(recipeId))
                },
                onBackClick = navigateBack,
                onGroceryListClick = { weekStart ->
                    navController.navigate(Screen.GroceryList.createRoute(weekStart))
                }
            )
        }

        composable(
            route = Screen.GroceryList.route,
            arguments = listOf(
                navArgument("weekStart") { type = NavType.StringType }
            )
        ) {
            GroceryListScreen(
                onBackClick = navigateBack
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBackClick = navigateBack,
                onNavigateToImportDebug = {
                    navController.navigate(Screen.ImportDebugList.route)
                },
                onNavigateToSyncLogs = {
                    navController.navigate(Screen.SyncLogs.route)
                },
                onNavigateToImportSelection = { importType, uri ->
                    navController.navigate(
                        Screen.ImportSelection.createRoute(importType, uri.toString())
                    )
                }
            )
        }

        composable(Screen.SyncLogs.route) {
            SyncLogScreen(
                onBackClick = navigateBack
            )
        }

        composable(Screen.ImportDebugList.route) {
            ImportDebugListScreen(
                onBackClick = navigateBack,
                onEntryClick = { debugEntryId ->
                    navController.navigate(Screen.ImportDebugDetail.createRoute(debugEntryId))
                }
            )
        }

        composable(
            route = Screen.ImportDebugDetail.route,
            arguments = listOf(
                navArgument("debugEntryId") { type = NavType.StringType }
            )
        ) {
            ImportDebugDetailScreen(
                onBackClick = navigateBack,
                onNavigateToRecipe = { recipeId ->
                    navController.navigate(Screen.RecipeDetail.createRoute(recipeId))
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
            val importTypeStr = backStackEntry.arguments?.getString("importType") ?: "lorecipes"
            val uriStr = backStackEntry.arguments?.getString("uri")
            val importType = when (importTypeStr) {
                "paprika" -> ImportSelectionViewModel.ImportType.PAPRIKA
                "zip" -> ImportSelectionViewModel.ImportType.ZIP_BACKUP
                else -> ImportSelectionViewModel.ImportType.LORECIPES
            }
            val title = stringResource(R.string.select_recipes_to_import)
            val importSelectionViewModel: ImportSelectionViewModel = hiltViewModel()
            val selectionState = importSelectionViewModel.uiState.collectAsStateWithLifecycle()

            LaunchedEffect(uriStr) {
                if (uriStr != null) {
                    val uri = uriStr.toUri()
                    importSelectionViewModel.parseFile(uri, importType)
                }
            }

            ImportSelectionScreen(
                title = title,
                state = selectionState.value,
                onToggleItem = importSelectionViewModel::toggleItem,
                onSelectAll = importSelectionViewModel::selectAll,
                onDeselectAll = importSelectionViewModel::deselectAll,
                onImportClick = {
                    importSelectionViewModel.importSelected { result ->
                        when (result) {
                            is ImportSelectionViewModel.ImportResult.PaprikaSelected -> {
                                // Navigate back to AddRecipe and start Paprika import
                                // with selected recipe names
                                navController.popBackStack()
                                // Find the AddRecipeViewModel and start import
                                // We pass the selected names via SharedImportState
                                SharedImportState.paprikaSelectedNames = result.selectedRecipeNames
                                SharedImportState.paprikaFileUri = result.fileUri
                                SharedImportState.pendingPaprikaImport = true
                            }
                            is ImportSelectionViewModel.ImportResult.DirectImportComplete -> {
                                if (result.importedCount == 1 && result.importedRecipeId != null) {
                                    navController.navigate(Screen.RecipeList.route) {
                                        popUpTo(0) { inclusive = true }
                                    }
                                    navController.navigate(
                                        Screen.RecipeDetail.createRoute(result.importedRecipeId)
                                    )
                                } else {
                                    navController.navigate(Screen.RecipeList.route) {
                                        popUpTo(0) { inclusive = true }
                                    }
                                    if (result.importedCount > 0) {
                                        Toast.makeText(
                                            context,
                                            context.getString(
                                                R.string.file_import_success_count,
                                                result.importedCount
                                            ),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                            is ImportSelectionViewModel.ImportResult.Error -> {
                                Toast.makeText(
                                    context,
                                    result.message,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                },
                onCancelClick = navigateBack
            )
        }
    }
}

/**
 * Simple in-memory state holder for passing import selection data between screens.
 * Used to communicate Paprika import selection from ImportSelectionScreen back to AddRecipeScreen.
 */
object SharedImportState {
    var paprikaSelectedNames: Set<String>? = null
    var paprikaFileUri: Uri? = null
    var pendingPaprikaImport: Boolean = false
}

