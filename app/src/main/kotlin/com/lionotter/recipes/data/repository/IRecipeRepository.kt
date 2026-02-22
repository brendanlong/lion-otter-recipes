package com.lionotter.recipes.data.repository

import com.lionotter.recipes.data.local.RecipeIdAndName
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.coroutines.flow.Flow

interface IRecipeRepository {
    fun getAllRecipes(): Flow<List<Recipe>>
    fun getRecipeById(id: String): Flow<Recipe?>
    suspend fun getRecipeByIdOnce(id: String): Recipe?
    fun saveRecipe(recipe: Recipe)
    fun deleteRecipe(id: String)
    fun setFavorite(id: String, isFavorite: Boolean)
    fun setUserNotes(id: String, userNotes: String?)
    fun setImageUrl(id: String, imageUrl: String?)
    fun updateTitleAndUrl(id: String, name: String, sourceUrl: String?)
    suspend fun getAllRecipeIdsAndNames(): List<RecipeIdAndName>
    suspend fun getRecipeCount(): Int
}
