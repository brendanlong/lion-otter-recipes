package com.lionotter.recipes.data.repository

import android.util.Log
import com.lionotter.recipes.data.local.RecipeIdAndName
import com.google.firebase.Firebase
import com.google.firebase.firestore.firestore
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
) {
    companion object {
        private const val TAG = "RecipeRepository"
    }

    fun getAllRecipes(): Flow<List<Recipe>> = callbackFlow {
        val registration = firestoreService.recipesCollection()
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
                        Log.e(TAG, "Failed to deserialize recipe ${doc.id}", e)
                        firestoreService.reportError("Failed to load recipe ${doc.id}: ${e.message}")
                        null
                    }
                } ?: emptyList()
                trySend(recipes)
            }
        awaitClose { registration.remove() }
    }

    fun getRecipeById(id: String): Flow<Recipe?> = callbackFlow {
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
                    Log.e(TAG, "Failed to deserialize recipe $id", e)
                    firestoreService.reportError("Failed to load recipe $id: ${e.message}")
                    null
                }
                trySend(recipe)
            }
        awaitClose { registration.remove() }
    }

    suspend fun getRecipeByIdOnce(id: String): Recipe? {
        return try {
            val snapshot = firestoreService.recipesCollection().document(id).get().await()
            snapshot.toObject(RecipeDto::class.java)?.toDomain()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get recipe $id", e)
            firestoreService.reportError("Failed to load recipe: ${e.message}")
            null
        }
    }

    suspend fun getOriginalHtml(id: String): String? {
        return try {
            val doc = firestoreService.recipesCollection()
                .document(id)
                .collection("content")
                .document("htmlDoc")
                .get()
                .await()
            doc.getString("originalHtml")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get original HTML for recipe $id", e)
            firestoreService.reportError("Failed to load recipe content: ${e.message}")
            null
        }
    }

    fun saveRecipe(recipe: Recipe, originalHtml: String? = null) {
        val dto = recipe.toDto()
        val docRef = firestoreService.recipesCollection().document(recipe.id)

        docRef.set(dto)
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to save recipe ${recipe.name}", e)
                firestoreService.reportError("Failed to save recipe: ${e.message}")
            }

        if (originalHtml != null) {
            docRef.collection("content").document("htmlDoc")
                .set(mapOf("originalHtml" to originalHtml))
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to save original HTML for recipe ${recipe.id}", e)
                }
        }
    }

    fun deleteRecipe(id: String) {
        val recipeDocRef = firestoreService.recipesCollection().document(id)
        val contentDocRef = recipeDocRef.collection("content").document("htmlDoc")

        Firebase.firestore.batch().apply {
            delete(contentDocRef)
            delete(recipeDocRef)
        }.commit().addOnFailureListener { e ->
            Log.e(TAG, "Failed to delete recipe $id", e)
            firestoreService.reportError("Failed to delete recipe: ${e.message}")
        }
    }

    fun setFavorite(id: String, isFavorite: Boolean) {
        firestoreService.recipesCollection().document(id)
            .update("isFavorite", isFavorite)
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to set favorite for recipe $id", e)
                firestoreService.reportError("Failed to update favorite: ${e.message}")
            }
    }

    suspend fun getAllRecipeIdsAndNames(): List<RecipeIdAndName> {
        return try {
            val snapshot = firestoreService.recipesCollection().get().await()
            snapshot.documents.mapNotNull { doc ->
                val name = doc.getString("name") ?: return@mapNotNull null
                RecipeIdAndName(id = doc.id, name = name)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get recipe IDs and names", e)
            emptyList()
        }
    }
}
