package com.lionotter.recipes.data.repository

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.firestore.firestore
import com.lionotter.recipes.data.remote.FirestoreService
import com.lionotter.recipes.data.remote.dto.MealPlanDto
import com.lionotter.recipes.data.remote.dto.toDto
import com.lionotter.recipes.domain.model.MealPlanEntry
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.datetime.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MealPlanRepository @Inject constructor(
    private val firestoreService: FirestoreService
) {
    companion object {
        private const val TAG = "MealPlanRepository"
    }

    fun getMealPlansForDateRange(startDate: LocalDate, endDate: LocalDate): Flow<List<MealPlanEntry>> = callbackFlow {
        val registration = firestoreService.mealPlansCollection()
            .whereGreaterThanOrEqualTo("date", startDate.toString())
            .whereLessThanOrEqualTo("date", endDate.toString())
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening to meal plans", error)
                    firestoreService.reportError("Failed to load meal plans: ${error.message}")
                    return@addSnapshotListener
                }
                val entries = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        doc.toObject(MealPlanDto::class.java)?.toDomain()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to deserialize meal plan ${doc.id}", e)
                        firestoreService.reportError("Failed to load meal plan ${doc.id}: ${e.message}")
                        null
                    }
                } ?: emptyList()
                trySend(entries)
            }
        awaitClose { registration.remove() }
    }

    fun saveMealPlan(entry: MealPlanEntry) {
        firestoreService.mealPlansCollection().document(entry.id)
            .set(entry.toDto())
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to save meal plan ${entry.id}", e)
                firestoreService.reportError("Failed to save meal plan: ${e.message}")
            }
    }

    fun updateMealPlan(entry: MealPlanEntry) {
        firestoreService.mealPlansCollection().document(entry.id)
            .set(entry.toDto())
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to update meal plan ${entry.id}", e)
                firestoreService.reportError("Failed to update meal plan: ${e.message}")
            }
    }

    fun deleteMealPlan(id: String) {
        firestoreService.mealPlansCollection().document(id)
            .delete()
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to delete meal plan $id", e)
                firestoreService.reportError("Failed to delete meal plan: ${e.message}")
            }
    }

    suspend fun deleteMealPlansByRecipeId(recipeId: String) {
        try {
            val snapshot = firestoreService.mealPlansCollection()
                .whereEqualTo("recipeId", recipeId)
                .get()
                .await()

            if (snapshot.documents.isNotEmpty()) {
                val batch = Firebase.firestore.batch()
                snapshot.documents.forEach { doc ->
                    batch.delete(doc.reference)
                }
                batch.commit().await()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete meal plans for recipe $recipeId", e)
            firestoreService.reportError("Failed to delete meal plans: ${e.message}")
        }
    }

    suspend fun countMealPlansByRecipeId(recipeId: String): Int {
        return try {
            val snapshot = firestoreService.mealPlansCollection()
                .whereEqualTo("recipeId", recipeId)
                .get()
                .await()
            snapshot.size()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to count meal plans for recipe $recipeId", e)
            0
        }
    }

    suspend fun getAllMealPlansOnce(): List<MealPlanEntry> {
        return try {
            val snapshot = firestoreService.mealPlansCollection().get().await()
            snapshot.documents.mapNotNull { doc ->
                try {
                    doc.toObject(MealPlanDto::class.java)?.toDomain()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to deserialize meal plan ${doc.id}", e)
                    firestoreService.reportError("Failed to load meal plan ${doc.id}: ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get all meal plans", e)
            emptyList()
        }
    }
}
