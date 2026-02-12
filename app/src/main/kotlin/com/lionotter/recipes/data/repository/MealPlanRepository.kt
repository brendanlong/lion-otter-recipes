package com.lionotter.recipes.data.repository

import android.util.Log
import com.lionotter.recipes.data.remote.FirestoreService
import com.lionotter.recipes.domain.model.MealPlanEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for meal plan data. Delegates all storage to Firestore via [FirestoreService].
 * Firestore's built-in offline cache provides offline access when network is disabled.
 */
@Singleton
class MealPlanRepository @Inject constructor(
    private val firestoreService: FirestoreService
) {
    companion object {
        private const val TAG = "MealPlanRepository"
    }

    /**
     * Observe all meal plans in real-time.
     */
    fun getAllMealPlans(): Flow<List<MealPlanEntry>> {
        return firestoreService.observeMealPlans()
    }

    /**
     * Observe meal plans for a specific date range in real-time.
     * Filters client-side since Firestore queries on date strings work correctly
     * for ISO-8601 formatted dates.
     */
    fun getMealPlansForDateRange(startDate: LocalDate, endDate: LocalDate): Flow<List<MealPlanEntry>> {
        return firestoreService.observeMealPlans().map { entries ->
            entries.filter { it.date in startDate..endDate }
                .sortedWith(
                    compareBy<MealPlanEntry> { it.date }
                        .thenBy { it.mealType.displayOrder }
                        .thenBy { it.recipeName }
                )
        }
    }

    /**
     * Get meal plans for a date range (one-shot).
     */
    suspend fun getMealPlansForDateRangeOnce(startDate: LocalDate, endDate: LocalDate): List<MealPlanEntry> {
        val result = firestoreService.getAllMealPlans()
        return result.getOrElse {
            Log.e(TAG, "Failed to get meal plans for date range", it)
            emptyList()
        }.filter { it.date in startDate..endDate }
    }

    /**
     * Get all meal plans (one-shot).
     */
    suspend fun getAllMealPlansOnce(): List<MealPlanEntry> {
        val result = firestoreService.getAllMealPlans()
        return result.getOrElse {
            Log.e(TAG, "Failed to get all meal plans", it)
            emptyList()
        }
    }

    /**
     * Get a single meal plan entry by ID (one-shot).
     */
    suspend fun getMealPlanByIdOnce(id: String): MealPlanEntry? {
        return firestoreService.getMealPlanById(id)
    }

    /**
     * Save a new meal plan entry.
     */
    suspend fun saveMealPlan(entry: MealPlanEntry) {
        val result = firestoreService.upsertMealPlan(entry)
        if (result.isFailure) {
            Log.e(TAG, "Failed to save meal plan: ${entry.id}", result.exceptionOrNull())
        }
    }

    /**
     * Update an existing meal plan entry.
     */
    suspend fun updateMealPlan(entry: MealPlanEntry) {
        val result = firestoreService.upsertMealPlan(entry)
        if (result.isFailure) {
            Log.e(TAG, "Failed to update meal plan: ${entry.id}", result.exceptionOrNull())
        }
    }

    /**
     * Delete a meal plan entry from Firestore.
     */
    suspend fun deleteMealPlan(id: String) {
        val result = firestoreService.deleteMealPlan(id)
        if (result.isFailure) {
            Log.e(TAG, "Failed to delete meal plan: $id", result.exceptionOrNull())
        }
    }

    /**
     * Count meal plans that reference a given recipe.
     */
    suspend fun countMealPlansByRecipeId(recipeId: String): Int {
        return firestoreService.countMealPlansByRecipeId(recipeId)
    }

    /**
     * Delete all meal plan entries that reference a given recipe.
     */
    suspend fun deleteMealPlansByRecipeId(recipeId: String) {
        val result = firestoreService.deleteMealPlansByRecipeId(recipeId)
        if (result.isFailure) {
            Log.e(TAG, "Failed to delete meal plans for recipe: $recipeId", result.exceptionOrNull())
        }
    }
}
