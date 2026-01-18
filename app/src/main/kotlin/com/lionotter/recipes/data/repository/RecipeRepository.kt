package com.lionotter.recipes.data.repository

import com.lionotter.recipes.data.local.RecipeDao
import com.lionotter.recipes.data.local.RecipeEntity
import com.lionotter.recipes.data.sync.DriveSyncManager
import com.lionotter.recipes.domain.model.IngredientSection
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Callback interface for triggering sync after repository operations.
 * This allows the repository to signal when sync work should be triggered
 * without directly depending on WorkManager.
 */
interface SyncTrigger {
    fun triggerUploadSync()
    fun triggerDeleteSync(operationId: Long)
}

@Singleton
class RecipeRepository @Inject constructor(
    private val recipeDao: RecipeDao,
    private val json: Json,
    private val syncManager: DriveSyncManager
) {
    /**
     * Optional sync trigger. Set this to receive callbacks when
     * sync operations should be triggered.
     */
    var syncTrigger: SyncTrigger? = null

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

    /**
     * Save a recipe and queue it for sync to Google Drive.
     */
    suspend fun saveRecipe(recipe: Recipe, originalHtml: String? = null) {
        val entity = RecipeEntity.fromRecipe(
            recipe = recipe,
            ingredientSectionsJson = json.encodeToString(recipe.ingredientSections),
            instructionSectionsJson = json.encodeToString(recipe.instructionSections),
            tagsJson = json.encodeToString(recipe.tags),
            originalHtml = originalHtml
        )
        recipeDao.insertRecipe(entity)

        // Queue for sync
        syncManager.queueUpload(recipe.id)
        syncTrigger?.triggerUploadSync()
    }

    /**
     * Delete a recipe and queue deletion from Google Drive.
     */
    suspend fun deleteRecipe(id: String) {
        // Queue delete BEFORE removing from local DB (we need the sync record)
        val wasQueued = syncManager.queueDelete(id)

        // Now delete locally
        recipeDao.deleteRecipeById(id)

        // Trigger delete sync if we queued an operation
        if (wasQueued) {
            // Get the operation ID from pending operations
            val pendingOps = syncManager.getPendingOperations()
            val deleteOp = pendingOps.find {
                it.localRecipeId == id &&
                it.operationType == com.lionotter.recipes.data.local.sync.SyncOperationType.DELETE
            }
            deleteOp?.let { syncTrigger?.triggerDeleteSync(it.id) }
        }
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

    /**
     * Get all recipes with their tags for tag ranking algorithms.
     * Returns a list of pairs: (recipeId, list of tags for that recipe)
     */
    suspend fun getAllRecipesWithTags(): List<Pair<String, List<String>>> {
        return recipeDao.getAllRecipesOnce().map { entity ->
            val tags: List<String> = try {
                json.decodeFromString(entity.tagsJson)
            } catch (e: Exception) {
                emptyList()
            }
            entity.id to tags
        }
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
