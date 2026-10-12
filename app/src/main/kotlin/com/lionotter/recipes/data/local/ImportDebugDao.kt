package com.lionotter.recipes.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The small columns of an [ImportDebugEntity], without the potentially multi-megabyte
 * HTML/content/JSON columns. Rows larger than the ~2MB CursorWindow limit throw
 * SQLiteBlobTooBigException, so lists and headers must use this projection.
 */
data class ImportDebugSummary(
    val id: String,
    val sourceUrl: String?,
    val originalLength: Int,
    val cleanedLength: Int,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val aiModel: String?,
    val thinkingEnabled: Boolean,
    val recipeId: String?,
    val recipeName: String?,
    val errorMessage: String?,
    val isError: Boolean,
    val durationMs: Long?,
    val createdAt: Long,
    val batchMode: Boolean
)

@Dao
interface ImportDebugDao {
    @Query("SELECT * FROM import_debug ORDER BY createdAt DESC")
    fun getAllDebugEntries(): Flow<List<ImportDebugEntity>>

    @Query("SELECT * FROM import_debug WHERE id = :id")
    suspend fun getDebugEntryById(id: String): ImportDebugEntity?

    @Query(
        "SELECT id, sourceUrl, originalLength, cleanedLength, inputTokens, outputTokens, aiModel, " +
            "thinkingEnabled, recipeId, recipeName, errorMessage, isError, durationMs, createdAt, " +
            "batchMode FROM import_debug ORDER BY createdAt DESC"
    )
    fun getAllDebugSummaries(): Flow<List<ImportDebugSummary>>

    @Query(
        "SELECT id, sourceUrl, originalLength, cleanedLength, inputTokens, outputTokens, aiModel, " +
            "thinkingEnabled, recipeId, recipeName, errorMessage, isError, durationMs, createdAt, " +
            "batchMode FROM import_debug WHERE id = :id"
    )
    suspend fun getDebugSummaryById(id: String): ImportDebugSummary?

    /** Length in characters of a large text column, or null if the column is NULL. */
    @Query(
        "SELECT CASE :column " +
            "WHEN 'originalHtml' THEN length(originalHtml) " +
            "WHEN 'cleanedContent' THEN length(cleanedContent) " +
            "WHEN 'aiOutputJson' THEN length(aiOutputJson) END " +
            "FROM import_debug WHERE id = :id"
    )
    suspend fun getContentLength(id: String, column: String): Int?

    /**
     * Reads [length] characters of a large text column starting at the 1-based [start]
     * offset, so callers can read big values in chunks that fit in a CursorWindow.
     */
    @Query(
        "SELECT CASE :column " +
            "WHEN 'originalHtml' THEN substr(originalHtml, :start, :length) " +
            "WHEN 'cleanedContent' THEN substr(cleanedContent, :start, :length) " +
            "WHEN 'aiOutputJson' THEN substr(aiOutputJson, :start, :length) END " +
            "FROM import_debug WHERE id = :id"
    )
    suspend fun getContentChunk(id: String, column: String, start: Int, length: Int): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDebugEntry(entry: ImportDebugEntity)

    @Query("DELETE FROM import_debug")
    suspend fun deleteAllDebugEntries()
}
