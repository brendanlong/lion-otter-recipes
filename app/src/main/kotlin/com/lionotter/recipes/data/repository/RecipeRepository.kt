package com.lionotter.recipes.data.repository

import android.util.Log
import com.google.firebase.firestore.FieldPath
import com.lionotter.recipes.data.local.RecipeIdAndName
import com.lionotter.recipes.data.remote.AuthService
import com.lionotter.recipes.data.remote.FirestoreService
import com.lionotter.recipes.data.remote.ImageDownloadService
import com.lionotter.recipes.data.remote.dto.RecipeDto
import com.lionotter.recipes.data.remote.dto.toDto
import com.lionotter.recipes.data.remote.uid
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.util.ErrorLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
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

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Single shared Firestore snapshot listener for all recipes.
     * All recipe queries (list and by-id) derive from this shared flow,
     * avoiding multiple redundant listeners.
     *
     * Combines [AuthState] with the Firestore [generation][FirestoreService.generation]
     * counter so the listener is re-created when either the user changes or the
     * Firestore instance is recycled (e.g., after clearLocalData during migration).
     *
     * Documents are decoded off the main thread, and only when they change.
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

    private fun recipesFlowForUser(uid: String): Flow<List<Recipe>> =
        firestoreService.recipesCollection(uid)
            .snapshots(firestoreService, TAG, "recipes")
            .decodeIncrementally(TAG) { doc -> doc.toObject(RecipeDto::class.java)?.toDomain() }
            .flowOn(Dispatchers.Default)
            .conflate()

    private fun write(action: String, block: (uid: String) -> Unit) =
        authService.writeForCurrentUser(repositoryScope, firestoreService, TAG, action, block)

    override fun getAllRecipes(): Flow<List<Recipe>> = allRecipesShared

    override fun getRecipeById(id: String): Flow<Recipe?> =
        allRecipesShared
            .map { recipes -> recipes.find { it.id == id } }
            .distinctUntilChanged()

    /**
     * Uses a document-ID query rather than `document(id).get()`: when offline (always,
     * for guests) a document get fails with UNAVAILABLE if the document isn't cached,
     * whereas a query resolves from the cache and is simply empty.
     *
     * @return the recipe, or null if it does not exist
     * @throws Exception if the recipe could not be read
     */
    override suspend fun getRecipeByIdOnce(id: String): Recipe? =
        logFailure(TAG, "Error fetching recipe $id") {
            val snapshot = firestoreService.recipesCollection(authService.awaitUid())
                .whereEqualTo(FieldPath.documentId(), id)
                .limit(1)
                .get()
                .await()
            val doc = snapshot.documents.firstOrNull() ?: return@logFailure null
            withContext(Dispatchers.Default) { doc.toObject(RecipeDto::class.java)?.toDomain() }
        }

    override fun saveRecipe(recipe: Recipe) = write("save recipe") { uid ->
        val dto = recipe.toDto()
        firestoreService.recipesCollection(uid).document(recipe.id).set(dto)
            .addOnFailureListener { e ->
                ErrorLog.e(TAG, "Error saving recipe ${recipe.id}", e)
                firestoreService.reportError("Failed to save recipe: ${e.message}")
            }
    }

    override fun deleteRecipe(id: String) = write("delete recipe") { uid ->
        // Read the recipe's image URL first, then delete everything.
        firestoreService.recipesCollection(uid).document(id).get()
            .addOnSuccessListener { snapshot ->
                val imageUrl = snapshot.getString("imageUrl")
                if (imageUrl != null) {
                    repositoryScope.launch {
                        withContext(NonCancellable) { imageDownloadService.cleanupImage(imageUrl, recipeId = id) }
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
                ErrorLog.e(TAG, "Error deleting recipe $id", e)
                firestoreService.reportError("Failed to delete recipe: ${e.message}")
            }
    }

    override fun setFavorite(id: String, isFavorite: Boolean) = write("update favorite") { uid ->
        firestoreService.recipesCollection(uid).document(id)
            .update("isFavorite", isFavorite)
            .addOnFailureListener { e ->
                ErrorLog.e(TAG, "Error updating favorite for $id", e)
                firestoreService.reportError("Failed to update favorite: ${e.message}")
            }
    }

    override fun setUserNotes(id: String, userNotes: String?) = write("update notes") { uid ->
        firestoreService.recipesCollection(uid).document(id)
            .update("userNotes", userNotes)
            .addOnFailureListener { e ->
                ErrorLog.e(TAG, "Error updating user notes for $id", e)
                firestoreService.reportError("Failed to update notes: ${e.message}")
            }
    }

    /** Also points the recipe's meal plans (which keep a copy of the image URL) at the new image. */
    override fun setImageUrl(id: String, imageUrl: String?) = write("update image") { uid ->
        firestoreService.recipesCollection(uid).document(id)
            .update("imageUrl", imageUrl)
            .addOnFailureListener { e ->
                ErrorLog.e(TAG, "Error updating image URL for $id", e)
                firestoreService.reportError("Failed to update image: ${e.message}")
            }
        firestoreService.mealPlansCollection(uid)
            .whereEqualTo("recipeId", id)
            .get()
            .addOnSuccessListener { snapshot ->
                for (doc in snapshot.documents) {
                    doc.reference.update("recipeImageUrl", imageUrl)
                        .addOnFailureListener { e -> Log.w(TAG, "Failed to update meal plan image ${doc.id}", e) }
                }
            }
            .addOnFailureListener { e -> Log.w(TAG, "Failed to update meal plan images for recipe $id", e) }
    }

    override fun updateTitleAndUrl(id: String, name: String, sourceUrl: String?) = write("update recipe") { uid ->
        val updates = mutableMapOf<String, Any?>(
            "name" to name,
            "sourceUrl" to sourceUrl
        )
        firestoreService.recipesCollection(uid).document(id)
            .update(updates)
            .addOnFailureListener { e ->
                ErrorLog.e(TAG, "Error updating title/URL for $id", e)
                firestoreService.reportError("Failed to update recipe: ${e.message}")
            }
    }

    /** @throws Exception if recipes could not be read */
    override suspend fun getAllRecipeIdsAndNames(): List<RecipeIdAndName> =
        logFailure(TAG, "Error fetching recipe IDs and names") {
            val snapshot = firestoreService.recipesCollection(authService.awaitUid()).get().await()
            snapshot.documents.mapNotNull { doc ->
                val name = doc.getString("name") ?: return@mapNotNull null
                RecipeIdAndName(id = doc.id, name = name)
            }
        }

    /** @throws Exception if recipes could not be read */
    override suspend fun getRecipeCount(): Int =
        logFailure(TAG, "Error fetching recipe count") {
            firestoreService.recipesCollection(authService.awaitUid()).get().await().size()
        }
}
