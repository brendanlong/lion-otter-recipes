package com.lionotter.recipes.data.repository

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.DocumentSnapshot
import com.lionotter.recipes.data.remote.AuthService
import com.lionotter.recipes.data.remote.DataFailure
import com.lionotter.recipes.data.remote.DataOp
import com.lionotter.recipes.data.remote.FirestoreService
import com.lionotter.recipes.data.remote.dto.MealPlanDto
import com.lionotter.recipes.data.remote.dto.MealPlanFields
import com.lionotter.recipes.data.remote.dto.toDto
import com.lionotter.recipes.data.remote.uid
import com.lionotter.recipes.di.ApplicationScope
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.repository.MealPlanRepository
import com.lionotter.recipes.util.DefaultDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FirestoreMealPlanRepository @Inject constructor(
    private val firestoreService: FirestoreService,
    private val authService: AuthService,
    @param:ApplicationScope private val appScope: CoroutineScope,
    @param:DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : MealPlanRepository {
    companion object {
        private const val TAG = "MealPlanRepository"
    }

    /** Runs [block]'s write for the current user, reporting a failure to start it or of its task as [op]. */
    private fun write(op: DataOp, id: String, block: (uid: String) -> Task<*>) {
        val failure = DataFailure(op, id)
        authService.writeForCurrentUser(appScope, firestoreService, failure) { uid ->
            block(uid).reportFailureTo(firestoreService, failure)
        }
    }

    private fun decodeMealPlan(doc: DocumentSnapshot): MealPlanEntry? =
        doc.toObject(MealPlanDto::class.java)?.toDomain()

    /**
     * Listens to meal plans in the date range for the current user. Like
     * [RecipeRepository], the listener is re-created when the user or the
     * Firestore instance ([FirestoreService.generation]) changes.
     */
    override fun getMealPlansForDateRange(startDate: LocalDate, endDate: LocalDate): Flow<List<MealPlanEntry>> =
        combine(authService.authState, firestoreService.generation) { state, _ -> state.uid }
            .flatMapLatest { uid ->
                if (uid == null) {
                    flowOf(emptyList())
                } else {
                    firestoreService.mealPlansCollection(uid)
                        .whereGreaterThanOrEqualTo(MealPlanFields.DATE, startDate.toString())
                        .whereLessThanOrEqualTo(MealPlanFields.DATE, endDate.toString())
                        .snapshots(firestoreService, DataOp.LOAD_MEAL_PLANS)
                        .decodeIncrementally(TAG, ::decodeMealPlan)
                        .flowOn(defaultDispatcher)
                }
            }
            .conflate()

    override fun saveMealPlan(entry: MealPlanEntry) = write(DataOp.SAVE_MEAL_PLAN, entry.id) { uid ->
        firestoreService.mealPlansCollection(uid).document(entry.id).set(entry.toDto())
    }

    override fun deleteMealPlan(id: String) = write(DataOp.DELETE_MEAL_PLAN, id) { uid ->
        firestoreService.mealPlansCollection(uid).document(id).delete()
    }

    /**
     * Deletes all meal plans referencing [recipeId]. Runs to completion even if the
     * caller is cancelled. The batch is applied to the local cache immediately and
     * synced in the background (it is not awaited, since that never completes offline);
     * a sync failure is reported through [FirestoreService.failures].
     *
     * @throws Exception if the meal plans could not be queried
     */
    override suspend fun deleteMealPlansByRecipeId(recipeId: String) = withContext(NonCancellable) {
        logFailure(TAG, "Error querying meal plans for recipe $recipeId") {
            val snapshot = firestoreService.mealPlansCollection(authService.awaitUid())
                .whereEqualTo(MealPlanFields.RECIPE_ID, recipeId)
                .get()
                .await()

            if (snapshot.documents.isEmpty()) return@logFailure

            val batch = firestoreService.batch()
            for (doc in snapshot.documents) {
                batch.delete(doc.reference)
            }
            batch.commit()
                .reportFailureTo(firestoreService, DataFailure(DataOp.DELETE_RECIPE_MEAL_PLANS, recipeId))
            Unit
        }
    }

    /** @throws Exception if meal plans could not be read */
    override suspend fun countMealPlansByRecipeIds(recipeIds: List<String>): Int {
        if (recipeIds.isEmpty()) return 0
        return logFailure(TAG, "Error counting meal plans for ${recipeIds.size} recipes") {
            val uid = authService.awaitUid()
            // Firestore whereIn supports max 30 values per query, so chunk if needed
            recipeIds.chunked(30).sumOf { chunk ->
                firestoreService.mealPlansCollection(uid)
                    .whereIn(MealPlanFields.RECIPE_ID, chunk)
                    .get()
                    .await()
                    .size()
            }
        }
    }

    /** @throws Exception if meal plans could not be read */
    override suspend fun getMealPlanCount(): Int =
        logFailure(TAG, "Error fetching meal plan count") {
            firestoreService.mealPlansCollection(authService.awaitUid()).get().await().size()
        }

    /**
     * Individual documents that fail to decode are logged and skipped.
     * @throws Exception if meal plans could not be read
     */
    override suspend fun getAllMealPlansOnce(): List<MealPlanEntry> =
        logFailure(TAG, "Error fetching all meal plans") {
            val snapshot = firestoreService.mealPlansCollection(authService.awaitUid()).get().await()
            withContext(defaultDispatcher) {
                snapshot.documents.mapNotNull { decodeSafely(TAG, it, ::decodeMealPlan) }
            }
        }

    /**
     * The updates are applied to the local cache at once and synced in the background; a sync
     * failure is logged (the meal plan keeps showing the old image until the next change).
     *
     * @throws Exception if the meal plans could not be queried (the caller logs it)
     */
    override suspend fun replaceRecipeImageUrl(oldUrl: String, newUrl: String) {
        val snapshot = firestoreService.mealPlansCollection(authService.awaitUid())
            .whereEqualTo(MealPlanFields.RECIPE_IMAGE_URL, oldUrl)
            .get()
            .await()
        for (doc in snapshot.documents) {
            doc.reference.update(MealPlanFields.RECIPE_IMAGE_URL, newUrl)
                .addOnFailureListener { e -> Log.w(TAG, "Failed to update meal plan image ${doc.id}", e) }
        }
    }
}
