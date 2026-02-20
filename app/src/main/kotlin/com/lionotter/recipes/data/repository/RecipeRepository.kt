package com.lionotter.recipes.data.repository

import android.util.Log
import com.google.firebase.firestore.SetOptions
import com.lionotter.recipes.data.local.RecipeIdAndName
import com.lionotter.recipes.data.remote.FirestoreService
import com.lionotter.recipes.data.remote.dto.RecipeDto
import com.lionotter.recipes.data.remote.dto.toDto
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecipeRepository @Inject constructor(
    private val firestoreService: FirestoreService
) : IRecipeRepository {
    companion object {
        private const val TAG = "RecipeRepository"
        private const val HTML_DOC_ID = "htmlDoc"
        private const val HTML_FIELD = "originalHtml"
    }

    override fun getAllRecipes(): Flow<List<Recipe>> = callbackFlow {
        val registration = firestoreService.recipesCollection()
            .orderBy("updatedAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening to recipes", error)
                    firestoreService.reportError("Failed to load recipes: ${error.message}")
                    return@addSnapshotListener
                }
                val recipes = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        doc.toObject(RecipeDto::class.java)?.toDomain()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error deserializing recipe ${doc.id}", e)
                        null
                    }
                } ?: emptyList()
                trySend(recipes)
            }
        awaitClose { registration.remove() }
    }

    override fun getRecipeById(id: String): Flow<Recipe?> = callbackFlow {
        val registration = firestoreService.recipesCollection().document(id)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening to recipe $id", error)
                    firestoreService.reportError("Failed to load recipe: ${error.message}")
                    return@addSnapshotListener
                }
                val recipe = try {
                    snapshot?.toObject(RecipeDto::class.java)?.toDomain()
                } catch (e: Exception) {
                    Log.e(TAG, "Error deserializing recipe $id", e)
                    null
                }
                trySend(recipe)
            }
        awaitClose { registration.remove() }
    }

    override suspend fun getRecipeByIdOnce(id: String): Recipe? {
        return try {
            val snapshot = firestoreService.recipesCollection().document(id).get().await()
            snapshot.toObject(RecipeDto::class.java)?.toDomain()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching recipe $id", e)
            null
        }
    }

    override suspend fun getOriginalHtml(recipeId: String): String? {
        return try {
            val snapshot = firestoreService.recipeContentCollection(recipeId)
                .document(HTML_DOC_ID).get().await()
            snapshot.getString(HTML_FIELD)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching original HTML for $recipeId", e)
            null
        }
    }

    override fun saveRecipe(recipe: Recipe, originalHtml: String?) {
        val dto = recipe.toDto()
        firestoreService.recipesCollection().document(recipe.id).set(dto)
            .addOnFailureListener { e ->
                Log.e(TAG, "Error saving recipe ${recipe.id}", e)
                firestoreService.reportError("Failed to save recipe: ${e.message}")
            }

        if (originalHtml != null) {
            firestoreService.recipeContentCollection(recipe.id)
                .document(HTML_DOC_ID)
                .set(mapOf(HTML_FIELD to originalHtml))
                .addOnFailureListener { e ->
                    Log.e(TAG, "Error saving original HTML for ${recipe.id}", e)
                }
        }
    }

    override fun deleteRecipe(id: String) {
        // Delete subcollection content doc first
        firestoreService.recipeContentCollection(id)
            .document(HTML_DOC_ID)
            .delete()
            .addOnFailureListener { e ->
                Log.e(TAG, "Error deleting content for recipe $id", e)
            }

        firestoreService.recipesCollection().document(id).delete()
            .addOnFailureListener { e ->
                Log.e(TAG, "Error deleting recipe $id", e)
                firestoreService.reportError("Failed to delete recipe: ${e.message}")
            }
    }

    override fun setFavorite(id: String, isFavorite: Boolean) {
        firestoreService.recipesCollection().document(id)
            .update("isFavorite", isFavorite)
            .addOnFailureListener { e ->
                Log.e(TAG, "Error updating favorite for $id", e)
                firestoreService.reportError("Failed to update favorite: ${e.message}")
            }
    }

    override suspend fun getAllRecipeIdsAndNames(): List<RecipeIdAndName> {
        return try {
            val snapshot = firestoreService.recipesCollection().get().await()
            snapshot.documents.mapNotNull { doc ->
                val name = doc.getString("name") ?: return@mapNotNull null
                RecipeIdAndName(id = doc.id, name = name)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching recipe IDs and names", e)
            emptyList()
        }
    }
}
