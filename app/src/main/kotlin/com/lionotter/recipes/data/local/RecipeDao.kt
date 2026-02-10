package com.lionotter.recipes.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecipeDao {
    @Query("SELECT * FROM recipes WHERE deleted = 0 ORDER BY updatedAt DESC")
    fun getAllRecipes(): Flow<List<RecipeEntity>>

    @Query("SELECT * FROM recipes WHERE id = :id")
    suspend fun getRecipeById(id: String): RecipeEntity?

    @Query("SELECT * FROM recipes WHERE id = :id AND deleted = 0")
    fun getRecipeByIdFlow(id: String): Flow<RecipeEntity?>

    @Query("SELECT * FROM recipes WHERE tagsJson LIKE '%' || :tag || '%' AND deleted = 0 ORDER BY updatedAt DESC")
    fun getRecipesByTag(tag: String): Flow<List<RecipeEntity>>

    @Query("SELECT * FROM recipes WHERE name LIKE '%' || :query || '%' AND deleted = 0 ORDER BY updatedAt DESC")
    fun searchRecipes(query: String): Flow<List<RecipeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecipe(recipe: RecipeEntity)

    @Update
    suspend fun updateRecipe(recipe: RecipeEntity)

    @Query("UPDATE recipes SET deleted = 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun softDeleteRecipe(id: String, updatedAt: Long)

    @Query("DELETE FROM recipes WHERE id = :id")
    suspend fun hardDeleteRecipeById(id: String)

    @Query("SELECT * FROM recipes WHERE deleted = 1")
    suspend fun getDeletedRecipes(): List<RecipeEntity>

    @Query("DELETE FROM recipes WHERE deleted = 1")
    suspend fun purgeDeletedRecipes()

    @Query("UPDATE recipes SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: String, isFavorite: Boolean)
}
