package com.lionotter.recipes.data.repository

import android.util.Log
import com.lionotter.recipes.data.local.RecipeIdAndName
import com.lionotter.recipes.data.remote.FirestoreService
import com.lionotter.recipes.data.remote.ImageDownloadService
import com.lionotter.recipes.data.remote.dto.RecipeDto
import com.lionotter.recipes.data.remote.dto.toDto
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecipeRepository @Inject constructor(
    private val firestoreService: FirestoreService,
    private val imageDownloadService: ImageDownloadService
) : IRecipeRepository {
    companion object {
        private const val TAG = "RecipeRepository"
    }

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Single shared Firestore snapshot listener for all recipes.
     * All recipe queries (list and by-id) derive from this shared flow,
     * avoiding multiple redundant listeners.
     */
    private val allRecipesShared: Flow<List<Recipe>> = callbackFlow {
        val collection = try {
            firestoreService.recipesCollection()
        } catch (e: Exception) {
            Log.e(TAG, "Error accessing recipes collection", e)
            close(e)
            return@callbackFlow
        }
        val listener = collection.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "Error listening to recipes", error)
                firestoreService.reportError("Failed to load recipes: ${error.message}")
                close(error)
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
        awaitClose { listener.remove() }
    }.conflate().shareIn(
        scope = repositoryScope,
        started = SharingStarted.WhileSubscribed(5000),
        replay = 1
    )

    override fun getAllRecipes(): Flow<List<Recipe>> = allRecipesShared

    override fun getRecipeById(id: String): Flow<Recipe?> =
        allRecipesShared
            .map { recipes -> recipes.find { it.id == id } }
            .distinctUntilChanged()

    override suspend fun getRecipeByIdOnce(id: String): Recipe? {
        return try {
            val snapshot = firestoreService.recipesCollection().document(id).get().await()
            snapshot.toObject(RecipeDto::class.java)?.toDomain()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching recipe $id", e)
            null
        }
    }

    override fun saveRecipe(recipe: Recipe) {
        val dto = recipe.toDto()
        firestoreService.recipesCollection().document(recipe.id).set(dto)
            .addOnFailureListener { e ->
                Log.e(TAG, "Error saving recipe ${recipe.id}", e)
                firestoreService.reportError("Failed to save recipe: ${e.message}")
            }
    }

    override fun deleteRecipe(id: String) {
        // Read the recipe's image URL first, then delete everything.
        // Deletion happens inside the listener to avoid a race where the
        // document is deleted before we read the image URL.
        firestoreService.recipesCollection().document(id).get()
            .addOnSuccessListener { snapshot ->
                val imageUrl = snapshot.getString("imageUrl")
                if (imageUrl != null) {
                    repositoryScope.launch {
                        imageDownloadService.cleanupImage(imageUrl)
                    }
                }
                deleteRecipeDocuments(id)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Could not read recipe image before delete: $id", e)
                // Still delete the recipe even if we couldn't read the image URL
                deleteRecipeDocuments(id)
            }
    }

    private fun deleteRecipeDocuments(id: String) {
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

    override fun setUserNotes(id: String, userNotes: String?) {
        firestoreService.recipesCollection().document(id)
            .update("userNotes", userNotes)
            .addOnFailureListener { e ->
                Log.e(TAG, "Error updating user notes for $id", e)
                firestoreService.reportError("Failed to update notes: ${e.message}")
            }
    }

    override fun setImageUrl(id: String, imageUrl: String?) {
        firestoreService.recipesCollection().document(id)
            .update("imageUrl", imageUrl)
            .addOnFailureListener { e ->
                Log.e(TAG, "Error updating image URL for $id", e)
                firestoreService.reportError("Failed to update image: ${e.message}")
            }
    }

    override fun updateTitleAndUrl(id: String, name: String, sourceUrl: String?) {
        val updates = mutableMapOf<String, Any?>(
            "name" to name,
            "sourceUrl" to sourceUrl
        )
        firestoreService.recipesCollection().document(id)
            .update(updates)
            .addOnFailureListener { e ->
                Log.e(TAG, "Error updating title/URL for $id", e)
                firestoreService.reportError("Failed to update recipe: ${e.message}")
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

    override suspend fun getRecipeCount(): Int {
        return try {
            val snapshot = firestoreService.recipesCollection().get().await()
            snapshot.size()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching recipe count", e)
            0
        }
    }
}
