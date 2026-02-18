package com.lionotter.recipes.data.repository

import com.lionotter.recipes.domain.model.MealPlanEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

interface IMealPlanRepository {
    fun getMealPlansForDateRange(startDate: LocalDate, endDate: LocalDate): Flow<List<MealPlanEntry>>
    fun saveMealPlan(entry: MealPlanEntry)
    fun updateMealPlan(entry: MealPlanEntry)
    fun deleteMealPlan(id: String)
    suspend fun deleteMealPlansByRecipeId(recipeId: String)
    suspend fun countMealPlansByRecipeId(recipeId: String): Int
    suspend fun getAllMealPlansOnce(): List<MealPlanEntry>
}
