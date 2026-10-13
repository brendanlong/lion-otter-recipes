package com.lionotter.recipes.domain.port

/** Keeps each AI import's input and output for the import debugging screen. */
interface ImportDebugLog {
    suspend fun record(entry: ImportDebugRecord)
}

data class ImportDebugRecord(
    val sourceUrl: String?,
    val originalHtml: String?,
    val cleanedContent: String?,
    val aiOutputJson: String?,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val aiModel: String?,
    val thinkingEnabled: Boolean,
    val recipeId: String?,
    val recipeName: String?,
    val errorMessage: String?,
    val isError: Boolean,
    val durationMs: Long?,
    val batchMode: Boolean = false
)
