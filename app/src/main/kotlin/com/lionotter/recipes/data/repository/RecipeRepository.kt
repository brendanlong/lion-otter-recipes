package com.lionotter.recipes.data.repository

import android.util.Log
import com.lionotter.recipes.data.local.RecipeDao
import com.lionotter.recipes.data.local.RecipeEntity
import com.lionotter.recipes.data.local.RecipeIdAndName
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
    private val _errors = MutableSharedFlow<RepositoryError>()
    val errors: SharedFlow<RepositoryError> = _errors.asSharedFlow()

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
            instructionSectionsJson = json.encodeToString(recipe.instructionSections),
            equipmentJson = json.encodeToString(recipe.equipment),
            tagsJson = json.encodeToString(recipe.tags),
            originalHtml = originalHtml
        )
        recipeDao.insertRecipe(entity)
    }

    suspend fun deleteRecipe(id: String) {
        recipeDao.deleteRecipe(id)
    }

    suspend fun setFavorite(id: String, isFavorite: Boolean) {
        recipeDao.setFavorite(id, isFavorite)
    }

    suspend fun setUserNotes(id: String, userNotes: String?) {
        recipeDao.setUserNotes(id, userNotes, kotlin.time.Clock.System.now())
    }

    suspend fun getAllRecipeIdsAndNames(): List<RecipeIdAndName> {
        return recipeDao.getAllRecipeIdsAndNames()
    }

    private inline fun <reified T> safeDecodeJson(
        jsonString: String,
        entityName: String,
        entityId: String,
        fieldName: String,
        default: T,
        onError: () -> Unit = {}
    ): T = try {
        json.decodeFromString(jsonString)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to parse $fieldName for recipe '$entityName' ($entityId)", e)
        onError()
        default
    }

    /**
     * Converts a database entity to a domain Recipe.
     * If JSON parsing fails for any section, logs the error, emits a typed error,
     * and returns the recipe with empty data for that section.
     */
    private suspend fun entityToRecipeWithErrorReporting(entity: RecipeEntity): Recipe {
        val failedFields = mutableListOf<String>()
        fun onError(field: String): () -> Unit = { failedFields.add(field) }

        val instructionSections: List<InstructionSection> = safeDecodeJson(
            entity.instructionSectionsJson, entity.name, entity.id, "instructions", emptyList(), onError("instructions")
        )
        val equipment: List<String> = safeDecodeJson(
            entity.equipmentJson, entity.name, entity.id, "equipment", emptyList(), onError("equipment")
        )
        val tags: List<String> = safeDecodeJson(
            entity.tagsJson, entity.name, entity.id, "tags", emptyList(), onError("tags")
        )

        if (failedFields.isNotEmpty()) {
            _errors.emit(
                RepositoryError.ParseError(
                    recipeId = entity.id,
                    recipeName = entity.name,
                    failedFields = failedFields
                )
            )
        }

        return entity.toRecipe(
            instructionSections = instructionSections,
            equipment = equipment,
            tags = tags
        )
    }

    /**
     * Non-suspending version for use in Flow mapping.
     * Logs errors but cannot emit to the error flow.
     */
    private fun entityToRecipe(entity: RecipeEntity): Recipe {
        val instructionSections: List<InstructionSection> = safeDecodeJson(
            entity.instructionSectionsJson, entity.name, entity.id, "instructions", emptyList()
        )
        val equipment: List<String> = safeDecodeJson(
            entity.equipmentJson, entity.name, entity.id, "equipment", emptyList()
        )
        val tags: List<String> = safeDecodeJson(
            entity.tagsJson, entity.name, entity.id, "tags", emptyList()
        )

        return entity.toRecipe(
            instructionSections = instructionSections,
            equipment = equipment,
            tags = tags
        )
    }
}
