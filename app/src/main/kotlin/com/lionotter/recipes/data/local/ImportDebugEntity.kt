package com.lionotter.recipes.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "import_debug")
data class ImportDebugEntity(
    @PrimaryKey
    val id: String,
    val sourceUrl: String?,
    val originalHtml: String?,
    val cleanedContent: String?,
    val aiOutputJson: String?,
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
    @ColumnInfo(defaultValue = "0")
    val batchMode: Boolean = false
)
