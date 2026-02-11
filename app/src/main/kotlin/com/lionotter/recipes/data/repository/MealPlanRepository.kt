package com.lionotter.recipes.data.repository

import com.lionotter.recipes.data.remote.FirestoreService
import com.lionotter.recipes.domain.model.MealPlanEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MealPlanRepository @Inject constructor(
    private val firestoreService: FirestoreService
) {
    fun getMealPlansForDateRange(startDate: LocalDate, endDate: LocalDate): Flow<List<MealPlanEntry>> {
        return firestoreService.observeMealPlans().map { entries ->
            entries.filter { it.date in startDate..endDate }
        }
    }

    suspend fun saveMealPlan(entry: MealPlanEntry) {
        firestoreService.upsertMealPlan(entry).getOrThrow()
    }

    suspend fun updateMealPlan(entry: MealPlanEntry) {
        firestoreService.upsertMealPlan(entry).getOrThrow()
    }

    suspend fun deleteMealPlan(id: String) {
        firestoreService.deleteMealPlan(id).getOrThrow()
    }

    suspend fun getMealPlanByIdOnce(id: String): MealPlanEntry? {
        return firestoreService.getMealPlanById(id)
    }

    fun getAllMealPlans(): Flow<List<MealPlanEntry>> {
        return firestoreService.observeMealPlans()
    }

    suspend fun getMealPlansForDateRangeOnce(startDate: LocalDate, endDate: LocalDate): List<MealPlanEntry> {
        return firestoreService.getAllMealPlans().getOrDefault(emptyList())
            .filter { it.date in startDate..endDate }
    }

    suspend fun getAllMealPlansOnce(): List<MealPlanEntry> {
        return firestoreService.getAllMealPlans().getOrDefault(emptyList())
    }
}
