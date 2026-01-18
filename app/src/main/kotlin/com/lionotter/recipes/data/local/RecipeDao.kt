package com.lionotter.recipes.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecipeDao {
    @Query("SELECT * FROM recipes ORDER BY updatedAt DESC")
    fun getAllRecipes(): Flow<List<RecipeEntity>>

    @Query("SELECT * FROM recipes ORDER BY updatedAt DESC")
    suspend fun getAllRecipesOnce(): List<RecipeEntity>

    @Query("SELECT * FROM recipes WHERE id = :id")
    suspend fun getRecipeById(id: String): RecipeEntity?

    @Query("SELECT * FROM recipes WHERE id = :id")
    fun getRecipeByIdFlow(id: String): Flow<RecipeEntity?>

    @Query("SELECT * FROM recipes WHERE tagsJson LIKE '%' || :tag || '%' ORDER BY updatedAt DESC")
    fun getRecipesByTag(tag: String): Flow<List<RecipeEntity>>

    @Query("SELECT * FROM recipes WHERE name LIKE '%' || :query || '%' ORDER BY updatedAt DESC")
    fun searchRecipes(query: String): Flow<List<RecipeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecipe(recipe: RecipeEntity)

    @Update
    suspend fun updateRecipe(recipe: RecipeEntity)

    @Delete
    suspend fun deleteRecipe(recipe: RecipeEntity)

    @Query("DELETE FROM recipes WHERE id = :id")
    suspend fun deleteRecipeById(id: String)

    @Query("SELECT DISTINCT tagsJson FROM recipes")
    suspend fun getAllTagsJson(): List<String>
}
