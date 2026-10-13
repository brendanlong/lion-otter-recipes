package com.lionotter.recipes.data.ai

import android.util.Log
import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.logDescription
import com.lionotter.recipes.domain.model.AiProviders
import com.lionotter.recipes.domain.model.ModelKind
import com.lionotter.recipes.domain.port.BatchItemOutcome
import com.lionotter.recipes.domain.port.BatchParseRequest
import com.lionotter.recipes.domain.port.BatchStatus
import com.lionotter.recipes.domain.port.ParseAttachment
import com.lionotter.recipes.domain.port.ParseRequest
import com.lionotter.recipes.domain.port.ParsedRecipeWithUsage
import com.lionotter.recipes.domain.port.RecipeBatchParser
import com.lionotter.recipes.domain.port.RecipeParser
import com.lionotter.recipes.util.ErrorLog
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** Parses recipes with OpenAI's Responses API: single streamed requests and the Batch API. */
@Singleton
class OpenAiRecipeParser @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json,
    private val responses: OpenAiResponses
) : RecipeParser, RecipeBatchParser {
    private val responseParser = RecipeResponseParser(json)

    override suspend fun parse(request: ParseRequest): Outcome<ParsedRecipeWithUsage> {
        val model = normalizeModel(request.model)
        val result = when (val response = responses.create(buildBody(request, model), request.apiKey)) {
            is Outcome.Success -> parseResponse(response.value)
            is Outcome.Failure -> response
        }
        if (result is Outcome.Failure) {
            Log.w(TAG, "Recipe parse failed (model=$model): ${result.error.logDescription()}")
        }
        return result
    }

    /** The request for [request]. Attachments go before the text: each photo in page order, or the PDF. */
    internal fun buildBody(request: ParseRequest, model: String): JsonObject {
        val text = RecipePrompt.buildUserMessage(
            request.content,
            request.densityOverrides,
            request.aiInstructions,
            request.existingTags,
            request.attachments
        )
        return recipeBody(
            model,
            request.attachments.map(::attachmentPart) + OpenAiResponses.inputText(text),
            request.thinkingEnabled
        )
    }

    private fun recipeBody(model: String, userContent: List<JsonObject>, thinkingEnabled: Boolean) =
        OpenAiResponses.requestBody(
            model = model,
            instructions = RecipePrompt.systemPrompt,
            userContent = userContent,
            schemaName = SCHEMA_NAME,
            schema = recipeSchema,
            effort = RecipeRequests.requestConfigFor(model, thinkingEnabled).effort,
            maxOutputTokens = RecipeRequests.MAX_OUTPUT_TOKENS
        )

    private fun attachmentPart(attachment: ParseAttachment): JsonObject = when (attachment) {
        is ParseAttachment.Image -> OpenAiResponses.inputImage(attachment.bytes, attachment.mediaType)
        is ParseAttachment.Pdf -> OpenAiResponses.inputFile(attachment.bytes, attachment.mediaType, PDF_FILENAME)
    }

    private fun parseResponse(response: JsonObject): Outcome<ParsedRecipeWithUsage> {
        val output = OpenAiResponses.outputOf(response)
        return responseParser.parseOutput(
            output.text,
            output.refused,
            output.truncated,
            output.inputTokens,
            output.outputTokens
        )
    }

    /** Batches cost half as much as single requests. The requests are uploaded as a JSONL file. */
    override suspend fun createBatch(
        requests: List<BatchParseRequest>,
        apiKey: String,
        model: String,
        thinkingEnabled: Boolean
    ): Outcome<String> {
        val resolvedModel = normalizeModel(model)
        val result = responses.call("Creating a batch") {
            val lines = buildString {
                for (request in requests) {
                    append(batchLine(request, resolvedModel, thinkingEnabled)).append('\n')
                }
            }
            val upload = httpClient.submitFormWithBinaryData(
                FILES_URL,
                formData {
                    append("purpose", "batch")
                    append(
                        "file",
                        lines.encodeToByteArray(),
                        Headers.build {
                            append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                            append(HttpHeaders.ContentDisposition, "filename=\"$BATCH_FILENAME\"")
                        }
                    )
                }
            ) {
                bearerAuth(apiKey)
                timeout { requestTimeoutMillis = TRANSFER_TIMEOUT_MILLIS; socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS }
            }
            if (!upload.status.isSuccess()) return@call responses.errorOf(upload)
            val fileId = json.decodeFromString<IdObject>(upload.bodyAsText()).id

            val batch = httpClient.post(BATCHES_URL) {
                bearerAuth(apiKey)
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("input_file_id", fileId)
                        put("endpoint", RESPONSES_ENDPOINT)
                        put("completion_window", "24h")
                    }.toString()
                )
            }
            if (!batch.status.isSuccess()) return@call responses.errorOf(batch)
            Outcome.Success(json.decodeFromString<IdObject>(batch.bodyAsText()).id)
        }
        if (result is Outcome.Failure) {
            ErrorLog.e(TAG, "Failed to create batch of ${requests.size} requests: ${result.error.logDescription()}")
        }
        return result
    }

    internal fun batchLine(request: BatchParseRequest, model: String, thinkingEnabled: Boolean): String {
        val text = RecipePrompt.buildUserMessage(
            request.content,
            request.densityOverrides,
            aiInstructions = null,
            existingTags = request.existingTags
        )
        return buildJsonObject {
            put("custom_id", request.customId)
            put("method", "POST")
            put("url", RESPONSES_ENDPOINT)
            put("body", recipeBody(model, listOf(OpenAiResponses.inputText(text)), thinkingEnabled))
        }.toString()
    }

    override suspend fun getBatchStatus(batchId: String, model: String, apiKey: String): Outcome<BatchStatus> {
        val result = responses.call("Retrieving batch $batchId") {
            when (val batch = fetchBatch(batchId, apiKey)) {
                is Outcome.Success -> statusOf(batch.value)
                is Outcome.Failure -> batch
            }
        }
        if (result is Outcome.Failure) {
            // Transient failures are retried; anything else ends the import, so report it.
            val message = "Failed to retrieve batch $batchId: ${result.error.logDescription()}"
            if (result.error.retryable) Log.w(TAG, message) else ErrorLog.e(TAG, message)
        }
        return result
    }

    private fun statusOf(batch: BatchObject): Outcome<BatchStatus> {
        if (batch.status == "failed") {
            Log.w(TAG, "Batch ${batch.id} failed: ${batch.errors?.data?.joinToString { it.code.orEmpty() }}")
            return Outcome.Failure(OpenAiErrors.error(AiFailure.REQUEST_REJECTED))
        }
        val counts = batch.requestCounts
        return Outcome.Success(
            BatchStatus(
                ended = batch.status in ENDED_STATUSES,
                succeeded = counts.completed,
                processing = (counts.total - counts.completed - counts.failed).coerceAtLeast(0)
            )
        )
    }

    override suspend fun cancelBatch(batchId: String, model: String, apiKey: String): Outcome<Unit> {
        val result = responses.call("Canceling batch $batchId") {
            val response = httpClient.post("$BATCHES_URL/$batchId/cancel") { bearerAuth(apiKey) }
            if (response.status.isSuccess()) Outcome.Success(Unit) else responses.errorOf(response)
        }
        if (result is Outcome.Failure) {
            Log.w(TAG, "Failed to cancel batch $batchId: ${result.error.logDescription()}")
        }
        return result
    }

    /** Reads the batch's output file, then its error file (failed, expired and canceled requests). */
    override suspend fun forEachResult(
        batchId: String,
        model: String,
        apiKey: String,
        skipCustomIds: Set<String>,
        onResult: (BatchItemOutcome) -> Unit
    ): Outcome<Unit> {
        val result = responses.call("Reading results for batch $batchId") {
            when (val batch = fetchBatch(batchId, apiKey)) {
                is Outcome.Success -> {
                    val files = listOfNotNull(batch.value.outputFileId, batch.value.errorFileId)
                    var outcome: Outcome<Unit> = Outcome.Success(Unit)
                    for (fileId in files) {
                        outcome = readResultFile(fileId, apiKey, skipCustomIds, onResult)
                        if (outcome is Outcome.Failure) break
                    }
                    outcome
                }
                is Outcome.Failure -> batch
            }
        }
        if (result is Outcome.Failure) {
            ErrorLog.e(TAG, "Failed to read results for batch $batchId: ${result.error.logDescription()}")
        }
        return result
    }

    private suspend fun fetchBatch(batchId: String, apiKey: String): Outcome<BatchObject> {
        val response = httpClient.get("$BATCHES_URL/$batchId") { bearerAuth(apiKey) }
        if (!response.status.isSuccess()) return responses.errorOf(response)
        return Outcome.Success(json.decodeFromString<BatchObject>(response.bodyAsText()))
    }

    /** Streams the JSONL file line by line, so the whole batch is never held in memory. */
    private suspend fun readResultFile(
        fileId: String,
        apiKey: String,
        skipCustomIds: Set<String>,
        onResult: (BatchItemOutcome) -> Unit
    ): Outcome<Unit> = httpClient.prepareGet("$FILES_URL/$fileId/content") {
        bearerAuth(apiKey)
        timeout { requestTimeoutMillis = TRANSFER_TIMEOUT_MILLIS; socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS }
    }.execute { response ->
        if (!response.status.isSuccess()) return@execute responses.errorOf(response)
        val channel = response.bodyAsChannel()
        while (true) {
            val line = channel.readLine() ?: break
            if (line.isBlank()) continue
            val result = json.decodeFromString<BatchResultLine>(line)
            if (result.customId !in skipCustomIds) onResult(toItemOutcome(result))
        }
        Outcome.Success(Unit)
    }

    private fun toItemOutcome(result: BatchResultLine): BatchItemOutcome {
        val customId = result.customId
        val response = result.response
        val body = response?.body
        if (response?.statusCode == HttpStatusCode.OK.value && body is JsonObject) {
            return when (val parsed = parseResponse(body)) {
                is Outcome.Success -> BatchItemOutcome.Parsed(customId, parsed.value)
                is Outcome.Failure -> BatchItemOutcome.Failed(customId, parsed.error.logDescription())
            }
        }
        val message = when (result.error?.code) {
            "batch_expired" -> "The AI batch expired before this recipe was processed"
            "batch_cancelled" -> "The AI batch was canceled"
            else -> if (response != null) {
                OpenAiErrors.forResponse(json, HttpStatusCode.fromValue(response.statusCode), body?.toString().orEmpty())
                    .logDescription()
            } else {
                "The AI request for this recipe failed"
            }
        }
        return BatchItemOutcome.Failed(customId, message)
    }

    private fun normalizeModel(model: String): String =
        AiProviders.OPENAI.findModel(model)?.takeIf { it.kind == ModelKind.TEXT }?.id ?: AiProviders.GPT_SOL

    @Serializable
    private data class IdObject(val id: String)

    @Serializable
    private data class BatchObject(
        val id: String,
        val status: String,
        @SerialName("request_counts") val requestCounts: RequestCounts = RequestCounts(),
        @SerialName("output_file_id") val outputFileId: String? = null,
        @SerialName("error_file_id") val errorFileId: String? = null,
        val errors: BatchErrors? = null
    )

    @Serializable
    private data class RequestCounts(val total: Int = 0, val completed: Int = 0, val failed: Int = 0)

    @Serializable
    private data class BatchErrors(val data: List<ErrorDetail> = emptyList())

    @Serializable
    private data class ErrorDetail(val code: String? = null)

    @Serializable
    private data class BatchResultLine(
        @SerialName("custom_id") val customId: String,
        val response: BatchResultResponse? = null,
        val error: ErrorDetail? = null
    )

    @Serializable
    private data class BatchResultResponse(
        @SerialName("status_code") val statusCode: Int,
        val body: JsonElement? = null
    )

    private companion object {
        const val TAG = "OpenAiRecipeParser"
        const val FILES_URL = "https://api.openai.com/v1/files"
        const val BATCHES_URL = "https://api.openai.com/v1/batches"
        const val RESPONSES_ENDPOINT = "/v1/responses"
        const val SCHEMA_NAME = "recipe_response"
        const val PDF_FILENAME = "recipe.pdf"
        const val BATCH_FILENAME = "batch.jsonl"
        const val TRANSFER_TIMEOUT_MILLIS = 15 * 60_000L
        const val SOCKET_TIMEOUT_MILLIS = 5 * 60_000L
        val ENDED_STATUSES = setOf("completed", "expired", "cancelled")
        val recipeSchema: JsonElement by lazy { OpenAiResponses.toJson(RecipeOutputSchema.RECIPE_RESPONSE_SCHEMA) }
    }
}
