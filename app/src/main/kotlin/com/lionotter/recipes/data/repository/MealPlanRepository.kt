package com.lionotter.recipes.data.repository

import com.google.firebase.Firebase
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.firestore
import com.lionotter.recipes.data.remote.AuthService
import com.lionotter.recipes.data.remote.FirestoreService
import com.lionotter.recipes.data.remote.dto.MealPlanDto
import com.lionotter.recipes.data.remote.dto.toDto
import com.lionotter.recipes.data.remote.uid
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.util.ErrorLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
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
class MealPlanRepository @Inject constructor(
    private val firestoreService: FirestoreService,
    private val authService: AuthService
) : IMealPlanRepository {
    companion object {
        private const val TAG = "MealPlanRepository"
    }

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun write(action: String, block: (uid: String) -> Unit) =
        authService.writeForCurrentUser(repositoryScope, firestoreService, TAG, action, block)

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
                        .whereGreaterThanOrEqualTo("date", startDate.toString())
                        .whereLessThanOrEqualTo("date", endDate.toString())
                        .snapshots(firestoreService, TAG, "meal plans")
                        .decodeIncrementally(TAG, ::decodeMealPlan)
                        .flowOn(Dispatchers.Default)
                }
            }
            .conflate()

    override fun saveMealPlan(entry: MealPlanEntry) = write("save meal plan") { uid ->
        val dto = entry.toDto()
        firestoreService.mealPlansCollection(uid).document(entry.id).set(dto)
            .addOnFailureListener { e ->
                ErrorLog.e(TAG, "Error saving meal plan ${entry.id}", e)
                firestoreService.reportError("Failed to save meal plan: ${e.message}")
            }
    }

    override fun updateMealPlan(entry: MealPlanEntry) = write("update meal plan") { uid ->
        val dto = entry.toDto()
        firestoreService.mealPlansCollection(uid).document(entry.id).set(dto)
            .addOnFailureListener { e ->
                ErrorLog.e(TAG, "Error updating meal plan ${entry.id}", e)
                firestoreService.reportError("Failed to update meal plan: ${e.message}")
            }
    }

    override fun deleteMealPlan(id: String) = write("delete meal plan") { uid ->
        firestoreService.mealPlansCollection(uid).document(id).delete()
            .addOnFailureListener { e ->
                ErrorLog.e(TAG, "Error deleting meal plan $id", e)
                firestoreService.reportError("Failed to delete meal plan: ${e.message}")
            }
    }

    /**
     * Deletes all meal plans referencing [recipeId]. Runs to completion even if the
     * caller is cancelled. The batch is applied to the local cache immediately and
     * synced in the background (it is not awaited, since that never completes offline).
     *
     * @throws Exception if the meal plans could not be queried
     */
    override suspend fun deleteMealPlansByRecipeId(recipeId: String) = withContext(NonCancellable) {
        logFailure(TAG, "Error querying meal plans for recipe $recipeId") {
            val snapshot = firestoreService.mealPlansCollection(authService.awaitUid())
                .whereEqualTo("recipeId", recipeId)
                .get()
                .await()

            if (snapshot.documents.isEmpty()) return@logFailure

            val batch = Firebase.firestore.batch()
            for (doc in snapshot.documents) {
                batch.delete(doc.reference)
            }
            batch.commit()
                .addOnFailureListener { e ->
                    ErrorLog.e(TAG, "Error batch deleting meal plans for recipe $recipeId", e)
                    firestoreService.reportError("Failed to delete meal plans: ${e.message}")
                }
            Unit
        }
    }

    /** @throws Exception if meal plans could not be read */
    override suspend fun countMealPlansByRecipeId(recipeId: String): Int =
        logFailure(TAG, "Error counting meal plans for recipe $recipeId") {
            firestoreService.mealPlansCollection(authService.awaitUid())
                .whereEqualTo("recipeId", recipeId)
                .get()
                .await()
                .size()
        }

    /** @throws Exception if meal plans could not be read */
    override suspend fun countMealPlansByRecipeIds(recipeIds: List<String>): Int {
        if (recipeIds.isEmpty()) return 0
        return logFailure(TAG, "Error counting meal plans for ${recipeIds.size} recipes") {
            val uid = authService.awaitUid()
            // Firestore whereIn supports max 30 values per query, so chunk if needed
            recipeIds.chunked(30).sumOf { chunk ->
                firestoreService.mealPlansCollection(uid)
                    .whereIn("recipeId", chunk)
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
            withContext(Dispatchers.Default) {
                snapshot.documents.mapNotNull { doc ->
                    try {
                        decodeMealPlan(doc)
                    } catch (e: Exception) {
                        ErrorLog.e(TAG, "Error deserializing meal plan ${doc.id}", e)
                        null
                    }
                }
            }
        }
}
