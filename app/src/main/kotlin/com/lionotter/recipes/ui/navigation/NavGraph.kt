package com.lionotter.recipes.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lionotter.recipes.ui.screens.addrecipe.AddRecipeScreen
import com.lionotter.recipes.ui.screens.recipedetail.RecipeDetailScreen
import com.lionotter.recipes.ui.screens.recipelist.RecipeListScreen
import com.lionotter.recipes.ui.screens.settings.SettingsScreen

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
}

@Composable
fun NavGraph(sharedUrl: String? = null) {
    val navController = rememberNavController()

    val startDestination = if (sharedUrl != null) {
        Screen.AddRecipe.createRoute(sharedUrl)
    } else {
        Screen.RecipeList.route
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
                    navController.navigate(Screen.AddRecipe.route)
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
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
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBackClick = navigateBack
            )
        }
    }
}
