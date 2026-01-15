package com.lionotter.recipes.data.repository

import com.lionotter.recipes.data.local.RecipeDao
import com.lionotter.recipes.data.local.RecipeEntity
import com.lionotter.recipes.domain.model.IngredientSection
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.coroutines.flow.Flow
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
        return recipeDao.getRecipeById(id)?.let { entityToRecipe(it) }
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

    suspend fun getAllTags(): Set<String> {
        val allTagsJson = recipeDao.getAllTagsJson()
        return allTagsJson.flatMap { tagsJson ->
            try {
                json.decodeFromString<List<String>>(tagsJson)
            } catch (e: Exception) {
                emptyList()
            }
        }.toSet()
    }

    private fun entityToRecipe(entity: RecipeEntity): Recipe {
        val ingredientSections: List<IngredientSection> = try {
            json.decodeFromString(entity.ingredientSectionsJson)
        } catch (e: Exception) {
            emptyList()
        }

        val instructionSections: List<InstructionSection> = try {
            json.decodeFromString(entity.instructionSectionsJson)
        } catch (e: Exception) {
            emptyList()
        }

        val tags: List<String> = try {
            json.decodeFromString(entity.tagsJson)
        } catch (e: Exception) {
            emptyList()
        }

        return entity.toRecipe(
            ingredientSections = ingredientSections,
            instructionSections = instructionSections,
            tags = tags
        )
    }
}
