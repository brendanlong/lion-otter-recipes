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
    object AddRecipe : Screen("add-recipe")
    object Settings : Screen("settings")
}

@Composable
fun NavGraph() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.RecipeList.route
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
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(Screen.AddRecipe.route) {
            AddRecipeScreen(
                onBackClick = { navController.popBackStack() },
                onRecipeAdded = { recipeId ->
                    navController.popBackStack()
                    navController.navigate(Screen.RecipeDetail.createRoute(recipeId))
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}
