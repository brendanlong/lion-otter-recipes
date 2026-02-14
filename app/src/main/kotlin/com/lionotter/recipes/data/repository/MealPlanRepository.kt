package com.lionotter.recipes.data.repository

import com.lionotter.recipes.data.local.MealPlanDao
import com.lionotter.recipes.data.local.MealPlanEntity
import com.lionotter.recipes.domain.model.MealPlanEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
        return mealPlanDao.getMealPlanById(id)?.toMealPlanEntry()
    }

    suspend fun saveMealPlan(entry: MealPlanEntry) {
        mealPlanDao.insertMealPlan(MealPlanEntity.fromMealPlanEntry(entry))
    }

    suspend fun updateMealPlan(entry: MealPlanEntry) {
        mealPlanDao.updateMealPlan(MealPlanEntity.fromMealPlanEntry(entry))
    }

    suspend fun deleteMealPlan(id: String) {
        mealPlanDao.deleteMealPlan(id)
    }

    suspend fun getAllMealPlansOnce(): List<MealPlanEntry> {
        return mealPlanDao.getAllMealPlansOnce().map { it.toMealPlanEntry() }
    }

    suspend fun getMealPlansForDateRangeOnce(startDate: LocalDate, endDate: LocalDate): List<MealPlanEntry> {
        return mealPlanDao.getMealPlansForDateRangeOnce(startDate.toString(), endDate.toString()).map { it.toMealPlanEntry() }
    }

    /**
     * Count meal plan entries that reference the given recipe.
     */
    suspend fun countMealPlansByRecipeId(recipeId: String): Int {
        return mealPlanDao.countMealPlansByRecipeId(recipeId)
    }

    /**
     * Delete all meal plan entries that reference the given recipe.
     */
    suspend fun deleteMealPlansByRecipeId(recipeId: String) {
        mealPlanDao.deleteMealPlansByRecipeId(recipeId)
    }
}
