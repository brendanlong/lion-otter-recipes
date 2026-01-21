package com.lionotter.recipes.data.repository

import android.util.Log
import com.lionotter.recipes.data.local.RecipeDao
import com.lionotter.recipes.data.local.RecipeEntity
import com.lionotter.recipes.domain.model.IngredientSection
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecipeRepository @Inject constructor(
    private val recipeDao: RecipeDao,
    private val json: Json
) {
    private val _errors = MutableSharedFlow<String>()
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private suspend fun emitError(message: String) {
        Log.e(TAG, message)
        _errors.emit(message)
    }

    companion object {
        private const val TAG = "RecipeRepository"
    }
    fun getAllRecipes(): Flow<List<Recipe>> {
        return recipeDao.getAllRecipes().map { entities ->
            entities.map { entity -> entityToRecipe(entity) }
        }
    }

    fun getRecipeById(id: String): Flow<Recipe?> {
        return recipeDao.getRecipeByIdFlow(id).map { entity ->
            entity?.let { entityToRecipe(it) }
        }
    }

    suspend fun getRecipeByIdOnce(id: String): Recipe? {
        return recipeDao.getRecipeById(id)?.let { entityToRecipeWithErrorReporting(it) }
    }

    /**
     * Get the original HTML content for a recipe.
     */
    suspend fun getOriginalHtml(id: String): String? {
        return recipeDao.getRecipeById(id)?.originalHtml
    }

    fun getRecipesByTag(tag: String): Flow<List<Recipe>> {
        return recipeDao.getRecipesByTag(tag).map { entities ->
            entities.map { entity -> entityToRecipe(entity) }
        }
    }

    fun searchRecipes(query: String): Flow<List<Recipe>> {
        return recipeDao.searchRecipes(query).map { entities ->
            entities.map { entity -> entityToRecipe(entity) }
        }
    }

    suspend fun saveRecipe(recipe: Recipe, originalHtml: String? = null) {
        val entity = RecipeEntity.fromRecipe(
            recipe = recipe,
            ingredientSectionsJson = json.encodeToString(recipe.ingredientSections),
            instructionSectionsJson = json.encodeToString(recipe.instructionSections),
            tagsJson = json.encodeToString(recipe.tags),
            originalHtml = originalHtml
        )
        recipeDao.insertRecipe(entity)
    }

    suspend fun deleteRecipe(id: String) {
        recipeDao.deleteRecipeById(id)
    }

    suspend fun setFavorite(id: String, isFavorite: Boolean) {
        recipeDao.setFavorite(id, isFavorite)
    }

    suspend fun getAllTags(): Set<String> {
        val allTagsJson = recipeDao.getAllTagsJson()
        return allTagsJson.flatMap { tagsJson ->
            try {
                json.decodeFromString<List<String>>(tagsJson)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse tags JSON: $tagsJson", e)
                emptyList()
            }
        }.toSet()
    }

    /**
     * Get all recipes with their tags for tag ranking algorithms.
     * Returns a list of pairs: (recipeId, list of tags for that recipe)
     */
    suspend fun getAllRecipesWithTags(): List<Pair<String, List<String>>> {
        return recipeDao.getAllRecipesOnce().map { entity ->
            val tags: List<String> = try {
                json.decodeFromString(entity.tagsJson)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse tags JSON for recipe ${entity.id}: ${entity.tagsJson}", e)
                emptyList()
            }
            entity.id to tags
        }
    }

    /**
     * Converts a database entity to a domain Recipe.
     * If JSON parsing fails for any section, logs the error, emits an error message,
     * and returns the recipe with empty data for that section.
     */
    private suspend fun entityToRecipeWithErrorReporting(entity: RecipeEntity): Recipe {
        var hasError = false

        val ingredientSections: List<IngredientSection> = try {
            json.decodeFromString(entity.ingredientSectionsJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ingredients for recipe '${entity.name}' (${entity.id})", e)
            hasError = true
            emptyList()
        }

        val instructionSections: List<InstructionSection> = try {
            json.decodeFromString(entity.instructionSectionsJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse instructions for recipe '${entity.name}' (${entity.id})", e)
            hasError = true
            emptyList()
        }

        val tags: List<String> = try {
            json.decodeFromString(entity.tagsJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse tags for recipe '${entity.name}' (${entity.id})", e)
            hasError = true
            emptyList()
        }

        if (hasError) {
            emitError("Some data for recipe '${entity.name}' could not be loaded. The recipe may appear incomplete.")
        }

        return entity.toRecipe(
            ingredientSections = ingredientSections,
            instructionSections = instructionSections,
            tags = tags
        )
    }

    /**
     * Non-suspending version for use in Flow mapping.
     * Logs errors but cannot emit to the error flow.
     */
    private fun entityToRecipe(entity: RecipeEntity): Recipe {
        val ingredientSections: List<IngredientSection> = try {
            json.decodeFromString(entity.ingredientSectionsJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ingredients for recipe '${entity.name}' (${entity.id})", e)
            emptyList()
        }

        val instructionSections: List<InstructionSection> = try {
            json.decodeFromString(entity.instructionSectionsJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse instructions for recipe '${entity.name}' (${entity.id})", e)
            emptyList()
        }

        val tags: List<String> = try {
            json.decodeFromString(entity.tagsJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse tags for recipe '${entity.name}' (${entity.id})", e)
            emptyList()
        }

        return entity.toRecipe(
            ingredientSections = ingredientSections,
            instructionSections = instructionSections,
            tags = tags
        )
    }
}
