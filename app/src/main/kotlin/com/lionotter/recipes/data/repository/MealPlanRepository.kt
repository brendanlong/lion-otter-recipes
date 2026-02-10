package com.lionotter.recipes.data.repository

import com.lionotter.recipes.data.local.MealPlanDao
import com.lionotter.recipes.data.local.MealPlanEntity
import com.lionotter.recipes.domain.model.MealPlanEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MealPlanRepository @Inject constructor(
    private val mealPlanDao: MealPlanDao
) {
    fun getAllMealPlans(): Flow<List<MealPlanEntry>> {
        return mealPlanDao.getAllMealPlans().map { entities ->
            entities.map { it.toMealPlanEntry() }
        }
    }

    fun getMealPlansForDateRange(startDate: LocalDate, endDate: LocalDate): Flow<List<MealPlanEntry>> {
        return mealPlanDao.getMealPlansForDateRange(startDate.toString(), endDate.toString()).map { entities ->
            entities.map { it.toMealPlanEntry() }
        }
    }

    fun getMealPlansForDate(date: LocalDate): Flow<List<MealPlanEntry>> {
        return mealPlanDao.getMealPlansForDate(date.toString()).map { entities ->
            entities.map { it.toMealPlanEntry() }
        }
    }

    suspend fun getMealPlanByIdOnce(id: String): MealPlanEntry? {
        return mealPlanDao.getMealPlanById(id)?.takeIf { !it.deleted }?.toMealPlanEntry()
    }

    suspend fun saveMealPlan(entry: MealPlanEntry) {
        mealPlanDao.insertMealPlan(MealPlanEntity.fromMealPlanEntry(entry))
    }

    suspend fun deleteMealPlan(id: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        mealPlanDao.softDeleteMealPlan(id, now)
    }

    suspend fun getDeletedMealPlans(): List<MealPlanEntry> {
        return mealPlanDao.getDeletedMealPlans().map { it.toMealPlanEntry() }
    }

    suspend fun purgeDeletedMealPlans() {
        mealPlanDao.purgeDeletedMealPlans()
    }

    suspend fun getAllMealPlansOnce(): List<MealPlanEntry> {
        return mealPlanDao.getAllMealPlansOnce().map { it.toMealPlanEntry() }
    }

    /**
     * Hard delete a meal plan (used during sync when remote deletion is detected).
     */
    suspend fun hardDeleteMealPlan(id: String) {
        mealPlanDao.hardDeleteMealPlan(id)
    }

    /**
     * Save a meal plan directly from sync (may include already-deleted entries from remote).
     */
    suspend fun saveMealPlanFromSync(entry: MealPlanEntry) {
        mealPlanDao.insertMealPlan(MealPlanEntity.fromMealPlanEntry(entry))
    }
}
