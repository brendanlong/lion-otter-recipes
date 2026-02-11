package com.lionotter.recipes.ui.navigation

import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
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
import com.lionotter.recipes.ui.screens.grocerylist.GroceryListScreen
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
    object ImportDebugList : Screen("import-debug")
    object ImportDebugDetail : Screen("import-debug/{debugEntryId}") {
        fun createRoute(debugEntryId: String) = "import-debug/$debugEntryId"
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
    val fileImportViewModel: FileImportViewModel = hiltViewModel()
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

    // Handle .lorecipes file import on launch
    LaunchedEffect(initialFileUri) {
        if (initialFileUri != null) {
            handleFileImport(fileImportViewModel, initialFileUri, context, navController)
        }
    }

    // Handle .lorecipes file shared/opened while app is running
    LaunchedEffect(sharedIntentViewModel) {
        sharedIntentViewModel?.sharedFileUri?.collectLatest { uri ->
            handleFileImport(fileImportViewModel, uri, context, navController)
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
                }
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
    }
}

private suspend fun handleFileImport(
    viewModel: FileImportViewModel,
    uri: Uri,
    context: android.content.Context,
    navController: androidx.navigation.NavController
) {
    val result = viewModel.importFromFile(uri)
    when (result) {
        is FileImportViewModel.ImportResult.Success -> {
            if (result.importedRecipeId != null) {
                // Navigate to the imported recipe
                navController.navigate(Screen.RecipeList.route) {
                    popUpTo(0) { inclusive = true }
                }
                navController.navigate(Screen.RecipeDetail.createRoute(result.importedRecipeId))
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.file_import_success),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        is FileImportViewModel.ImportResult.AlreadyExists -> {
            if (result.existingRecipeId != null) {
                navController.navigate(Screen.RecipeList.route) {
                    popUpTo(0) { inclusive = true }
                }
                navController.navigate(Screen.RecipeDetail.createRoute(result.existingRecipeId))
            }
            Toast.makeText(
                context,
                context.getString(R.string.file_import_already_exists),
                Toast.LENGTH_SHORT
            ).show()
        }
        is FileImportViewModel.ImportResult.Error -> {
            Toast.makeText(
                context,
                result.message,
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
