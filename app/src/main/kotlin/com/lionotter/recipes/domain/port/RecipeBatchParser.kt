package com.lionotter.recipes.domain.port

import com.lionotter.recipes.domain.Outcome

/**
 * Parses many recipes in one AI batch (cheaper, but takes minutes to hours): create the batch,
 * poll it until it has ended, then read the results. Batches are billed once created, so
 * callers must keep the batch ID somewhere durable.
 */
interface RecipeBatchParser {
    /** Returns the batch ID. */
    suspend fun createBatch(
        requests: List<BatchParseRequest>,
        apiKey: String,
        model: String,
        thinkingEnabled: Boolean
    ): Outcome<String>

    suspend fun getBatchStatus(batchId: String, apiKey: String): Outcome<BatchStatus>

    /**
     * Hands each result of an ended batch to [onResult] as it is downloaded, so the whole batch
     * is never held in memory. [onResult] runs while the download is open, so it must be quick.
     * Results for [skipCustomIds] (already handled by an earlier run) aren't parsed or passed on.
     */
    suspend fun forEachResult(
        batchId: String,
        apiKey: String,
        skipCustomIds: Set<String> = emptySet(),
        onResult: (BatchItemOutcome) -> Unit
    ): Outcome<Unit>

    suspend fun cancelBatch(batchId: String, apiKey: String): Outcome<Unit>
}

data class BatchParseRequest(
    val customId: String,
    val content: String,
    val densityOverrides: Map<String, Double>? = null,
    /** Tags the user already has (most used first), for the AI to reuse. */
    val existingTags: List<String> = emptyList()
)

data class BatchStatus(
    /** No request is still processing; results can be read. */
    val ended: Boolean,
    val succeeded: Int,
    val processing: Int
)

sealed interface BatchItemOutcome {
    val customId: String

    data class Parsed(override val customId: String, val result: ParsedRecipeWithUsage) : BatchItemOutcome

    /** [message] says what went wrong, for logs and import debugging. */
    data class Failed(override val customId: String, val message: String) : BatchItemOutcome
}
