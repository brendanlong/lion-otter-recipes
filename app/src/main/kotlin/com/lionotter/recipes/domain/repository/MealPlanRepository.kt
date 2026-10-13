package com.lionotter.recipes.domain.repository

import com.lionotter.recipes.domain.model.MealPlanEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

/**
 * Meal plans for the current user. [saveMealPlan], [updateMealPlan] and [deleteMealPlan] follow
 * the write contract described on [RecipeRepository]: applied locally at once, never throwing,
 * with sync failures reported through `FirestoreService.failures`. The suspending functions,
 * including [deleteMealPlansByRecipeId] (which has to query first), throw on failure.
 */
interface MealPlanRepository {
    fun getMealPlansForDateRange(startDate: LocalDate, endDate: LocalDate): Flow<List<MealPlanEntry>>
    fun saveMealPlan(entry: MealPlanEntry)
    fun updateMealPlan(entry: MealPlanEntry)
    fun deleteMealPlan(id: String)
    suspend fun deleteMealPlansByRecipeId(recipeId: String)
    suspend fun countMealPlansByRecipeId(recipeId: String): Int
    suspend fun countMealPlansByRecipeIds(recipeIds: List<String>): Int
    suspend fun getAllMealPlansOnce(): List<MealPlanEntry>
    suspend fun getMealPlanCount(): Int
}
