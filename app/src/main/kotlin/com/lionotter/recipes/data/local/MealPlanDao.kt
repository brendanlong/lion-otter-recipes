package com.lionotter.recipes.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MealPlanDao {

    @Query("SELECT * FROM meal_plans WHERE deleted = 0 ORDER BY date ASC, mealType ASC, recipeName ASC")
    fun getAllMealPlans(): Flow<List<MealPlanEntity>>

    @Query("SELECT * FROM meal_plans WHERE date BETWEEN :startDate AND :endDate AND deleted = 0 ORDER BY date ASC, mealType ASC, recipeName ASC")
    fun getMealPlansForDateRange(startDate: String, endDate: String): Flow<List<MealPlanEntity>>

    @Query("SELECT * FROM meal_plans WHERE date = :date AND deleted = 0 ORDER BY mealType ASC, recipeName ASC")
    fun getMealPlansForDate(date: String): Flow<List<MealPlanEntity>>

    @Query("SELECT * FROM meal_plans WHERE id = :id")
    suspend fun getMealPlanById(id: String): MealPlanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMealPlan(mealPlan: MealPlanEntity)

    @Update
    suspend fun updateMealPlan(mealPlan: MealPlanEntity)

    @Query("DELETE FROM meal_plans WHERE id = :id")
    suspend fun hardDeleteMealPlan(id: String)

    @Query("UPDATE meal_plans SET deleted = 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun softDeleteMealPlan(id: String, updatedAt: Long)

    @Query("SELECT * FROM meal_plans WHERE deleted = 1")
    suspend fun getDeletedMealPlans(): List<MealPlanEntity>

    @Query("DELETE FROM meal_plans WHERE deleted = 1")
    suspend fun purgeDeletedMealPlans()

    @Query("SELECT * FROM meal_plans WHERE deleted = 0")
    suspend fun getAllMealPlansOnce(): List<MealPlanEntity>
}
