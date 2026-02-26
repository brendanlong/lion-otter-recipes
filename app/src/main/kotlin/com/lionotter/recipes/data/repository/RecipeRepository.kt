package com.lionotter.recipes.data.repository

import android.util.Log
import com.lionotter.recipes.data.local.RecipeIdAndName
import com.lionotter.recipes.data.remote.AuthService
import com.lionotter.recipes.data.remote.AuthState
import com.lionotter.recipes.data.remote.FirestoreService
import com.lionotter.recipes.data.remote.ImageDownloadService
import com.lionotter.recipes.data.remote.dto.RecipeDto
import com.lionotter.recipes.data.remote.dto.toDto
import com.lionotter.recipes.data.remote.uid
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class RecipeRepository @Inject constructor(
    private val firestoreService: FirestoreService,
    private val imageDownloadService: ImageDownloadService,
    private val authService: AuthService
) : IRecipeRepository {
    companion object {
        private const val TAG = "RecipeRepository"
    }

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Single shared Firestore snapshot listener for all recipes.
     * All recipe queries (list and by-id) derive from this shared flow,
     * avoiding multiple redundant listeners.
     *
     * Combines [AuthState] with the Firestore [generation][FirestoreService.generation]
     * counter so the listener is re-created when either the user changes or the
     * Firestore instance is recycled (e.g., after clearLocalData during migration).
     */
    private val allRecipesShared: Flow<List<Recipe>> = combine(
        authService.authState,
        firestoreService.generation
    ) { state, _ -> state.uid }
        .flatMapLatest { uid ->
            if (uid == null) {
                flowOf(emptyList())
            } else {
                recipesFlowForUser(uid)
            }
        }
        .shareIn(
            scope = repositoryScope,
            started = SharingStarted.WhileSubscribed(5000),
            replay = 1
        )

    private fun recipesFlowForUser(uid: String): Flow<List<Recipe>> = callbackFlow {
        val collection = firestoreService.recipesCollection(uid)
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
    }.conflate()

    private fun requireUid(): String =
        authService.authState.value.uid
            ?: throw IllegalStateException("User not authenticated")

    override fun getAllRecipes(): Flow<List<Recipe>> = allRecipesShared

    override fun getRecipeById(id: String): Flow<Recipe?> =
        allRecipesShared
            .map { recipes -> recipes.find { it.id == id } }
            .distinctUntilChanged()

    override suspend fun getRecipeByIdOnce(id: String): Recipe? {
        return try {
            val snapshot = firestoreService.recipesCollection(requireUid()).document(id).get().await()
            snapshot.toObject(RecipeDto::class.java)?.toDomain()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching recipe $id", e)
            null
        }
    }

    override fun saveRecipe(recipe: Recipe) {
        val dto = recipe.toDto()
        firestoreService.recipesCollection(requireUid()).document(recipe.id).set(dto)
            .addOnFailureListener { e ->
                Log.e(TAG, "Error saving recipe ${recipe.id}", e)
                firestoreService.reportError("Failed to save recipe: ${e.message}")
            }
    }

    override fun deleteRecipe(id: String) {
        val uid = requireUid()
        // Read the recipe's image URL first, then delete everything.
        firestoreService.recipesCollection(uid).document(id).get()
            .addOnSuccessListener { snapshot ->
                val imageUrl = snapshot.getString("imageUrl")
                if (imageUrl != null) {
                    repositoryScope.launch {
                        imageDownloadService.cleanupImage(imageUrl)
                    }
                }
                deleteRecipeDocuments(uid, id)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Could not read recipe image before delete: $id", e)
                // Still delete the recipe even if we couldn't read the image URL
                deleteRecipeDocuments(uid, id)
            }
    }

    private fun deleteRecipeDocuments(uid: String, id: String) {
        firestoreService.recipesCollection(uid).document(id).delete()
            .addOnFailureListener { e ->
                Log.e(TAG, "Error deleting recipe $id", e)
                firestoreService.reportError("Failed to delete recipe: ${e.message}")
            }
    }

    override fun setFavorite(id: String, isFavorite: Boolean) {
        firestoreService.recipesCollection(requireUid()).document(id)
            .update("isFavorite", isFavorite)
            .addOnFailureListener { e ->
                Log.e(TAG, "Error updating favorite for $id", e)
                firestoreService.reportError("Failed to update favorite: ${e.message}")
            }
    }

    override fun setUserNotes(id: String, userNotes: String?) {
        firestoreService.recipesCollection(requireUid()).document(id)
            .update("userNotes", userNotes)
            .addOnFailureListener { e ->
                Log.e(TAG, "Error updating user notes for $id", e)
                firestoreService.reportError("Failed to update notes: ${e.message}")
            }
    }

    override fun setImageUrl(id: String, imageUrl: String?) {
        firestoreService.recipesCollection(requireUid()).document(id)
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
        firestoreService.recipesCollection(requireUid()).document(id)
            .update(updates)
            .addOnFailureListener { e ->
                Log.e(TAG, "Error updating title/URL for $id", e)
                firestoreService.reportError("Failed to update recipe: ${e.message}")
            }
    }

    override suspend fun getAllRecipeIdsAndNames(): List<RecipeIdAndName> {
        return try {
            val snapshot = firestoreService.recipesCollection(requireUid()).get().await()
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
            val snapshot = firestoreService.recipesCollection(requireUid()).get().await()
            snapshot.size()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching recipe count", e)
            0
        }
    }
}
