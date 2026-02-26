package com.lionotter.recipes.data.repository

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.firestore.firestore
import com.lionotter.recipes.data.remote.AuthService
import com.lionotter.recipes.data.remote.FirestoreService
import com.lionotter.recipes.data.remote.dto.MealPlanDto
import com.lionotter.recipes.data.remote.dto.toDto
import com.lionotter.recipes.data.remote.uid
import com.lionotter.recipes.domain.model.MealPlanEntry
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.tasks.await
import kotlinx.datetime.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MealPlanRepository @Inject constructor(
    private val firestoreService: FirestoreService,
    private val authService: AuthService
) : IMealPlanRepository {
    companion object {
        private const val TAG = "MealPlanRepository"
    }

    private fun requireUid(): String =
        authService.authState.value.uid
            ?: throw IllegalStateException("User not authenticated")

    override fun getMealPlansForDateRange(startDate: LocalDate, endDate: LocalDate): Flow<List<MealPlanEntry>> = callbackFlow {
        val uid = requireUid()
        val collection = firestoreService.mealPlansCollection(uid)
        val listener = collection
            .whereGreaterThanOrEqualTo("date", startDate.toString())
            .whereLessThanOrEqualTo("date", endDate.toString())
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening to meal plans", error)
                    firestoreService.reportError("Failed to load meal plans: ${error.message}", error)
                    close(error)
                    return@addSnapshotListener
                }
                val entries = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        doc.toObject(MealPlanDto::class.java)?.toDomain()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error deserializing meal plan ${doc.id}", e)
                        null
                    }
                } ?: emptyList()
                trySend(entries)
            }
        awaitClose { listener.remove() }
    }.conflate()

    override fun saveMealPlan(entry: MealPlanEntry) {
        val dto = entry.toDto()
        firestoreService.mealPlansCollection(requireUid()).document(entry.id).set(dto)
            .addOnFailureListener { e ->
                Log.e(TAG, "Error saving meal plan ${entry.id}", e)
                firestoreService.reportError("Failed to save meal plan: ${e.message}", e)
            }
    }

    override fun updateMealPlan(entry: MealPlanEntry) {
        val dto = entry.toDto()
        firestoreService.mealPlansCollection(requireUid()).document(entry.id).set(dto)
            .addOnFailureListener { e ->
                Log.e(TAG, "Error updating meal plan ${entry.id}", e)
                firestoreService.reportError("Failed to update meal plan: ${e.message}", e)
            }
    }

    override fun deleteMealPlan(id: String) {
        firestoreService.mealPlansCollection(requireUid()).document(id).delete()
            .addOnFailureListener { e ->
                Log.e(TAG, "Error deleting meal plan $id", e)
                firestoreService.reportError("Failed to delete meal plan: ${e.message}", e)
            }
    }

    override suspend fun deleteMealPlansByRecipeId(recipeId: String) {
        try {
            val uid = requireUid()
            val snapshot = firestoreService.mealPlansCollection(uid)
                .whereEqualTo("recipeId", recipeId)
                .get()
                .await()

            if (snapshot.documents.isEmpty()) return

            val batch = Firebase.firestore.batch()
            for (doc in snapshot.documents) {
                batch.delete(doc.reference)
            }
            batch.commit()
                .addOnFailureListener { e ->
                    Log.e(TAG, "Error batch deleting meal plans for recipe $recipeId", e)
                    firestoreService.reportError("Failed to delete meal plans: ${e.message}", e)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying meal plans for recipe $recipeId", e)
            firestoreService.reportError("Failed to delete meal plans: ${e.message}", e)
        }
    }

    override suspend fun countMealPlansByRecipeId(recipeId: String): Int {
        return try {
            val snapshot = firestoreService.mealPlansCollection(requireUid())
                .whereEqualTo("recipeId", recipeId)
                .get()
                .await()
            snapshot.size()
        } catch (e: Exception) {
            Log.e(TAG, "Error counting meal plans for recipe $recipeId", e)
            0
        }
    }

    override suspend fun countMealPlansByRecipeIds(recipeIds: List<String>): Int {
        if (recipeIds.isEmpty()) return 0
        return try {
            val uid = requireUid()
            // Firestore whereIn supports max 30 values per query, so chunk if needed
            recipeIds.chunked(30).sumOf { chunk ->
                val snapshot = firestoreService.mealPlansCollection(uid)
                    .whereIn("recipeId", chunk)
                    .get()
                    .await()
                snapshot.size()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error counting meal plans for ${recipeIds.size} recipes", e)
            0
        }
    }

    override suspend fun getMealPlanCount(): Int {
        return try {
            val snapshot = firestoreService.mealPlansCollection(requireUid()).get().await()
            snapshot.size()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching meal plan count", e)
            0
        }
    }

    override suspend fun getAllMealPlansOnce(): List<MealPlanEntry> {
        return try {
            val snapshot = firestoreService.mealPlansCollection(requireUid()).get().await()
            snapshot.documents.mapNotNull { doc ->
                try {
                    doc.toObject(MealPlanDto::class.java)?.toDomain()
                } catch (e: Exception) {
                    Log.e(TAG, "Error deserializing meal plan ${doc.id}", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching all meal plans", e)
            emptyList()
        }
    }
}
