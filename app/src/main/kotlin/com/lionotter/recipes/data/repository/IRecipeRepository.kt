package com.lionotter.recipes.data.repository

import com.lionotter.recipes.data.local.RecipeIdAndName
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.coroutines.flow.Flow

interface IRecipeRepository {
    fun getAllRecipes(): Flow<List<Recipe>>
    fun getRecipeById(id: String): Flow<Recipe?>
    suspend fun getRecipeByIdOnce(id: String): Recipe?
    suspend fun getOriginalHtml(recipeId: String): String?
    fun saveRecipe(recipe: Recipe, originalHtml: String? = null)
    fun deleteRecipe(id: String)
    fun setFavorite(id: String, isFavorite: Boolean)
    suspend fun getAllRecipeIdsAndNames(): List<RecipeIdAndName>
}
