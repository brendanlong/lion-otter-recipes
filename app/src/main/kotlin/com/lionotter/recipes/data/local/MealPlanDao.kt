package com.lionotter.recipes.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MealPlanDao {

    @Query("SELECT * FROM meal_plans ORDER BY date ASC, mealType ASC, recipeName ASC")
    fun getAllMealPlans(): Flow<List<MealPlanEntity>>

    @Query("SELECT * FROM meal_plans WHERE date BETWEEN :startDate AND :endDate ORDER BY date ASC, mealType ASC, recipeName ASC")
    fun getMealPlansForDateRange(startDate: String, endDate: String): Flow<List<MealPlanEntity>>

    @Query("SELECT * FROM meal_plans WHERE date = :date ORDER BY mealType ASC, recipeName ASC")
    fun getMealPlansForDate(date: String): Flow<List<MealPlanEntity>>

    @Query("SELECT * FROM meal_plans WHERE id = :id")
    suspend fun getMealPlanById(id: String): MealPlanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMealPlan(mealPlan: MealPlanEntity)

    @Update
    suspend fun updateMealPlan(mealPlan: MealPlanEntity)

    @Query("DELETE FROM meal_plans WHERE id = :id")
    suspend fun deleteMealPlan(id: String)

    @Query("SELECT * FROM meal_plans")
    suspend fun getAllMealPlansOnce(): List<MealPlanEntity>

    @Query("SELECT * FROM meal_plans WHERE date BETWEEN :startDate AND :endDate ORDER BY date ASC, mealType ASC, recipeName ASC")
    suspend fun getMealPlansForDateRangeOnce(startDate: String, endDate: String): List<MealPlanEntity>

    @Query("SELECT COUNT(*) FROM meal_plans WHERE recipeId = :recipeId")
    suspend fun countMealPlansByRecipeId(recipeId: String): Int

    @Query("DELETE FROM meal_plans WHERE recipeId = :recipeId")
    suspend fun deleteMealPlansByRecipeId(recipeId: String)
}
