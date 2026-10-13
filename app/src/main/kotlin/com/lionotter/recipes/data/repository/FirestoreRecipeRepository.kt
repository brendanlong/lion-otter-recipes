package com.lionotter.recipes.data.repository

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FieldPath
import com.lionotter.recipes.data.remote.AuthService
import com.lionotter.recipes.data.remote.DataFailure
import com.lionotter.recipes.data.remote.DataOp
import com.lionotter.recipes.data.remote.FirestoreService
import com.lionotter.recipes.data.remote.ImageDownloadService
import com.lionotter.recipes.data.remote.dto.MealPlanFields
import com.lionotter.recipes.data.remote.dto.RecipeDto
import com.lionotter.recipes.data.remote.dto.RecipeFields
import com.lionotter.recipes.data.remote.dto.toDto
import com.lionotter.recipes.data.remote.uid
import com.lionotter.recipes.di.ApplicationScope
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.RecipeIdAndName
import com.lionotter.recipes.domain.repository.RecipeRepository
import com.lionotter.recipes.util.DefaultDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
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
class FirestoreRecipeRepository @Inject constructor(
    private val firestoreService: FirestoreService,
    private val imageDownloadService: ImageDownloadService,
    private val authService: AuthService,
    @param:ApplicationScope private val appScope: CoroutineScope,
    @param:DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : RecipeRepository {
    companion object {
        private const val TAG = "RecipeRepository"
    }

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
            scope = appScope,
            started = SharingStarted.WhileSubscribed(5000),
            replay = 1
        )

    private fun recipesFlowForUser(uid: String): Flow<List<Recipe>> =
        firestoreService.recipesCollection(uid)
            .snapshots(firestoreService, DataOp.LOAD_RECIPES)
            .decodeIncrementally(TAG) { doc -> doc.toObject(RecipeDto::class.java)?.toDomain() }
            .flowOn(defaultDispatcher)
            .conflate()

    private fun write(op: DataOp, id: String, block: (uid: String) -> Unit) =
        authService.writeForCurrentUser(appScope, firestoreService, DataFailure(op, id), block)

    private fun <T> Task<T>.reportFailure(op: DataOp, id: String) =
        reportFailureTo(firestoreService, DataFailure(op, id))

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
            withContext(defaultDispatcher) { doc.toObject(RecipeDto::class.java)?.toDomain() }
        }

    override fun saveRecipe(recipe: Recipe) = write(DataOp.SAVE_RECIPE, recipe.id) { uid ->
        firestoreService.recipesCollection(uid).document(recipe.id).set(recipe.toDto())
            .reportFailure(DataOp.SAVE_RECIPE, recipe.id)
    }

    override fun deleteRecipe(id: String) = write(DataOp.DELETE_RECIPE, id) { uid ->
        // Read the recipe's image URL first, then delete everything.
        firestoreService.recipesCollection(uid).document(id).get()
            .addOnSuccessListener { snapshot ->
                val imageUrl = snapshot.getString(RecipeFields.IMAGE_URL)
                if (imageUrl != null) {
                    appScope.launch {
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
            .reportFailure(DataOp.DELETE_RECIPE, id)
    }

    override fun setFavorite(id: String, isFavorite: Boolean) = write(DataOp.UPDATE_FAVORITE, id) { uid ->
        firestoreService.recipesCollection(uid).document(id)
            .update(RecipeFields.IS_FAVORITE, isFavorite)
            .reportFailure(DataOp.UPDATE_FAVORITE, id)
    }

    override fun setUserNotes(id: String, userNotes: String?) = write(DataOp.UPDATE_NOTES, id) { uid ->
        firestoreService.recipesCollection(uid).document(id)
            .update(RecipeFields.USER_NOTES, userNotes)
            .reportFailure(DataOp.UPDATE_NOTES, id)
    }

    /** Also points the recipe's meal plans (which keep a copy of the image URL) at the new image. */
    override fun setImageUrl(id: String, imageUrl: String?) = write(DataOp.UPDATE_IMAGE, id) { uid ->
        firestoreService.recipesCollection(uid).document(id)
            .update(RecipeFields.IMAGE_URL, imageUrl)
            .reportFailure(DataOp.UPDATE_IMAGE, id)
        firestoreService.mealPlansCollection(uid)
            .whereEqualTo(MealPlanFields.RECIPE_ID, id)
            .get()
            .addOnSuccessListener { snapshot ->
                for (doc in snapshot.documents) {
                    doc.reference.update(MealPlanFields.RECIPE_IMAGE_URL, imageUrl)
                        .addOnFailureListener { e -> Log.w(TAG, "Failed to update meal plan image ${doc.id}", e) }
                }
            }
            .addOnFailureListener { e -> Log.w(TAG, "Failed to update meal plan images for recipe $id", e) }
    }

    override fun updateTitleAndUrl(id: String, name: String, sourceUrl: String?) = write(DataOp.UPDATE_RECIPE, id) { uid ->
        val updates = mutableMapOf<String, Any?>(
            RecipeFields.NAME to name,
            RecipeFields.SOURCE_URL to sourceUrl
        )
        firestoreService.recipesCollection(uid).document(id)
            .update(updates)
            .reportFailure(DataOp.UPDATE_RECIPE, id)
    }

    /** @throws Exception if recipes could not be read */
    override suspend fun getAllRecipeIdsAndNames(): List<RecipeIdAndName> =
        logFailure(TAG, "Error fetching recipe IDs and names") {
            val snapshot = firestoreService.recipesCollection(authService.awaitUid()).get().await()
            snapshot.documents.mapNotNull { doc ->
                val name = doc.getString(RecipeFields.NAME) ?: return@mapNotNull null
                RecipeIdAndName(id = doc.id, name = name)
            }
        }

    /** @throws Exception if recipes could not be read */
    override suspend fun getRecipeCount(): Int =
        logFailure(TAG, "Error fetching recipe count") {
            firestoreService.recipesCollection(authService.awaitUid()).get().await().size()
        }
}
