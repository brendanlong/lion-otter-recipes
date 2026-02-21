package com.lionotter.recipes.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

@Dao
interface RecipeDao {
    @Query("SELECT * FROM recipes ORDER BY updatedAt DESC")
    fun getAllRecipes(): Flow<List<RecipeEntity>>

    @Query("SELECT * FROM recipes WHERE id = :id")
    suspend fun getRecipeById(id: String): RecipeEntity?

    @Query("SELECT * FROM recipes WHERE id = :id")
    fun getRecipeByIdFlow(id: String): Flow<RecipeEntity?>

    @Query("SELECT * FROM recipes WHERE tagsJson LIKE '%' || :tag || '%' ORDER BY updatedAt DESC")
    fun getRecipesByTag(tag: String): Flow<List<RecipeEntity>>

    @Query("SELECT * FROM recipes WHERE name LIKE '%' || :query || '%' ORDER BY updatedAt DESC")
    fun searchRecipes(query: String): Flow<List<RecipeEntity>>

    @Query("SELECT id, name FROM recipes")
    suspend fun getAllRecipeIdsAndNames(): List<RecipeIdAndName>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecipe(recipe: RecipeEntity)

    @Update
    suspend fun updateRecipe(recipe: RecipeEntity)

    @Query("DELETE FROM recipes WHERE id = :id")
    suspend fun deleteRecipe(id: String)

    @Query("UPDATE recipes SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: String, isFavorite: Boolean)

    @Query("UPDATE recipes SET userNotes = :userNotes, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setUserNotes(id: String, userNotes: String?, updatedAt: Instant)
}
