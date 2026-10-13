package com.lionotter.recipes.data.ai

import android.util.Log
import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.model.AiProviders
import com.lionotter.recipes.domain.port.BatchItemOutcome
import com.lionotter.recipes.domain.port.BatchParseRequest
import com.lionotter.recipes.domain.port.BatchStatus
import com.lionotter.recipes.domain.port.ParseAttachment
import com.lionotter.recipes.domain.port.ParseRequest
import com.lionotter.recipes.domain.port.ParsedRecipeWithUsage
import com.lionotter.recipes.util.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

class OpenAiRecipeParserTest {
    private val requests = mutableListOf<HttpRequestData>()

    /** Responses by "METHOD path". */
    private val routes = mutableMapOf<String, Pair<HttpStatusCode, String>>()

    private val client = HttpClient(MockEngine) {
        install(HttpTimeout)
        engine {
            addHandler { request ->
                requests += request
                val (status, body) = routes["${request.method.value} ${request.url.encodedPath}"]
                    ?: (HttpStatusCode.NotFound to """{"error":{"message":"no route","code":null}}""")
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
    }
    private val parser = OpenAiRecipeParser(client, AppJson, OpenAiResponses(client, AppJson))

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private val recipeJson =
        """{"success":true,"recipe":{"name":"Toast","instructionSections":[{"steps":[{"stepNumber":1,"instruction":"Toast the bread."}]}],"equipment":[],"tags":[]}}"""

    private fun responseObject(
        content: String = """{"type":"output_text","text":${JsonPrimitive(recipeJson)},"annotations":[]}""",
        status: String = "completed",
        incompleteReason: String? = null
    ) = """{"id":"resp_1","object":"response","status":"$status",
        "incomplete_details":${incompleteReason?.let { """{"reason":"$it"}""" } ?: "null"},
        "output":[{"type":"reasoning","id":"rs_1","summary":[]},
                  {"type":"message","id":"msg_1","role":"assistant","content":[$content]}],
        "usage":{"input_tokens":10,"output_tokens":20,"total_tokens":30}}""".replace("\n", "")

    private fun stream(vararg events: Pair<String, String>) =
        events.joinToString("") { (type, data) -> "event: $type\ndata: $data\n\n" }

    private fun completedStream(response: String = responseObject(), terminal: String = "response.completed") = stream(
        "response.created" to """{"type":"response.created","sequence_number":0,"response":{"id":"resp_1","status":"in_progress"}}""",
        "response.output_text.delta" to """{"type":"response.output_text.delta","sequence_number":1,"delta":"{\"succ"}""",
        terminal to """{"type":"$terminal","sequence_number":2,"response":$response}"""
    )

    private fun request(model: String = AiProviders.GPT_SOL, thinking: Boolean = false, attachments: List<ParseAttachment> = emptyList()) =
        ParseRequest("# Toast", "sk-openai", model, thinking, existingTags = listOf("Breakfast"), attachments = attachments)

    private suspend fun HttpRequestData.bodyText(): String = body.toByteArray().decodeToString()

    private suspend fun HttpRequestData.json(): JsonObject = Json.parseToJsonElement(bodyText()).jsonObject

    private val JsonElement.type get() = jsonObject.getValue("type").jsonPrimitive.content

    private fun Outcome<ParsedRecipeWithUsage>.value(): ParsedRecipeWithUsage =
        (this as? Outcome.Success)?.value ?: throw AssertionError("Expected success, got $this")

    @Test
    fun `the request uses the model, the cached instructions and the strict recipe schema`() {
        val body = parser.buildBody(request(), AiProviders.GPT_SOL)

        assertEquals(AiProviders.GPT_SOL, body.getValue("model").jsonPrimitive.content)
        assertEquals(RecipePrompt.systemPrompt, body.getValue("instructions").jsonPrimitive.content)
        val format = body.getValue("text").jsonObject.getValue("format").jsonObject
        assertEquals("json_schema", format.getValue("type").jsonPrimitive.content)
        assertTrue(format.getValue("strict").jsonPrimitive.boolean)
        assertEquals(OpenAiResponses.toJson(RecipeOutputSchema.RECIPE_RESPONSE_SCHEMA), format.getValue("schema"))
        assertEquals("low", body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
        assertEquals(RecipeRequests.MAX_OUTPUT_TOKENS, body.getValue("max_output_tokens").jsonPrimitive.long)
        assertFalse(body.getValue("store").jsonPrimitive.boolean)
        assertFalse("stream" in body)

        val thinking = parser.buildBody(request(thinking = true), AiProviders.GPT_SOL)
        assertEquals("medium", thinking.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
    }

    @Test
    fun `Luna skips reasoning with thinking off, the other models can't`() {
        fun effort(model: String, thinking: Boolean) =
            parser.buildBody(request(thinking = thinking), model).getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content
        assertEquals("none", effort(AiProviders.GPT_LUNA, false))
        assertEquals("medium", effort(AiProviders.GPT_LUNA, true))
        assertEquals("low", effort(AiProviders.GPT_ASTRA, false))
    }

    @Test
    fun `attachments go before the text, and don't change the instructions`() {
        val photo = byteArrayOf(1, 2, 3)
        val pdf = "%PDF-1.4".toByteArray()
        val withPhotos = parser.buildBody(
            request(attachments = listOf(ParseAttachment.Image(photo, "image/jpeg"), ParseAttachment.Image(byteArrayOf(4), "image/png"))),
            AiProviders.GPT_SOL
        )
        val message = withPhotos.getValue("input").jsonArray.single().jsonObject
        assertEquals("user", message.getValue("role").jsonPrimitive.content)
        val content = message.getValue("content").jsonArray
        assertEquals(listOf("input_image", "input_image", "input_text"), content.map { it.type })
        assertEquals(
            "data:image/jpeg;base64,${Base64.getEncoder().encodeToString(photo)}",
            content[0].jsonObject.getValue("image_url").jsonPrimitive.content
        )
        assertTrue(content[1].jsonObject.getValue("image_url").jsonPrimitive.content.startsWith("data:image/png;base64,"))
        val text = content[2].jsonObject.getValue("text").jsonPrimitive.content
        assertTrue(text, text.contains("The 2 attached photos show one recipe"))
        assertTrue(text, text.contains("Breakfast"))

        val withPdf = parser.buildBody(request(attachments = listOf(ParseAttachment.Pdf(pdf))), AiProviders.GPT_SOL)
        val pdfContent = withPdf.getValue("input").jsonArray.single().jsonObject.getValue("content").jsonArray
        assertEquals(listOf("input_file", "input_text"), pdfContent.map { it.type })
        val file = pdfContent.first().jsonObject
        assertEquals("recipe.pdf", file.getValue("filename").jsonPrimitive.content)
        assertEquals(
            "data:application/pdf;base64,${Base64.getEncoder().encodeToString(pdf)}",
            file.getValue("file_data").jsonPrimitive.content
        )

        val textOnly = parser.buildBody(request(), AiProviders.GPT_SOL)
        assertEquals(listOf("input_text"), textOnly.getValue("input").jsonArray.single().jsonObject.getValue("content").jsonArray.map { it.type })
        assertEquals(textOnly.getValue("instructions"), withPhotos.getValue("instructions"))
    }

    @Test
    fun `a streamed response becomes a recipe`() = runTest {
        routes["POST /v1/responses"] = HttpStatusCode.OK to completedStream()

        val result = parser.parse(request(model = "unknown-model")).value()

        assertEquals("Toast", result.recipe.name)
        assertEquals(10L, result.inputTokens)
        assertEquals(20L, result.outputTokens)
        val sent = requests.single()
        assertEquals("https://api.openai.com/v1/responses", sent.url.toString())
        assertEquals("Bearer sk-openai", sent.headers[HttpHeaders.Authorization])
        val body = sent.json()
        assertTrue(body.getValue("stream").jsonPrimitive.boolean)
        assertEquals(AiProviders.GPT_SOL, body.getValue("model").jsonPrimitive.content)
    }

    @Test
    fun `refusals, truncation and failed responses are AI failures`() = runTest {
        routes["POST /v1/responses"] = HttpStatusCode.OK to completedStream(
            responseObject(content = """{"type":"refusal","refusal":"I can't help with that."}""")
        )
        assertEquals(Outcome.Failure(RecipeError.Ai(AiFailure.REFUSED)), parser.parse(request()))

        routes["POST /v1/responses"] = HttpStatusCode.OK to completedStream(
            responseObject(content = """{"type":"output_text","text":"{\"succ"}""", status = "incomplete", incompleteReason = "max_output_tokens"),
            terminal = "response.incomplete"
        )
        assertEquals(Outcome.Failure(RecipeError.Ai(AiFailure.TRUNCATED)), parser.parse(request()))

        routes["POST /v1/responses"] = HttpStatusCode.OK to stream(
            "response.failed" to """{"type":"response.failed","response":{"id":"resp_1","status":"failed","error":{"code":"rate_limit_exceeded","message":"m"}}}"""
        )
        assertEquals(
            Outcome.Failure(RecipeError.Ai(AiFailure.RATE_LIMITED, provider = AiProviders.OPENAI_ID)),
            parser.parse(request())
        )

        routes["POST /v1/responses"] = HttpStatusCode.OK to stream(
            "error" to """{"type":"error","code":"invalid_prompt","message":"m","param":null}"""
        )
        assertEquals(
            Outcome.Failure(RecipeError.Ai(AiFailure.UNKNOWN, provider = AiProviders.OPENAI_ID)),
            parser.parse(request())
        )

        // As the API sends it when the account has no credits left
        routes["POST /v1/responses"] = HttpStatusCode.OK to stream(
            "error" to """{"type":"error","error":{"type":"insufficient_quota","code":"credit_balance_exhausted","message":"m","param":null},"sequence_number":2}"""
        )
        assertEquals(
            Outcome.Failure(RecipeError.Ai(AiFailure.OUT_OF_CREDITS, provider = AiProviders.OPENAI_ID)),
            parser.parse(request())
        )

        routes["POST /v1/responses"] = HttpStatusCode.OK to stream(
            "response.created" to """{"type":"response.created","response":{"id":"resp_1","status":"in_progress"}}"""
        )
        assertEquals(
            Outcome.Failure(RecipeError.Ai(AiFailure.NETWORK, provider = AiProviders.OPENAI_ID)),
            parser.parse(request())
        )
    }

    @Test
    fun `an invalid key is reported for OpenAI`() = runTest {
        routes["POST /v1/responses"] =
            HttpStatusCode.Unauthorized to """{"error":{"message":"Incorrect API key","type":"invalid_request_error","code":"invalid_api_key"}}"""

        assertEquals(
            Outcome.Failure(RecipeError.Ai(AiFailure.INVALID_API_KEY, 401, AiProviders.OPENAI_ID)),
            parser.parse(request())
        )
    }

    @Test
    fun `a batch uploads the requests as JSONL, then creates the batch`() = runTest {
        routes["POST /v1/files"] = HttpStatusCode.OK to """{"id":"file-in","object":"file","purpose":"batch"}"""
        routes["POST /v1/batches"] = HttpStatusCode.OK to """{"id":"batch_1","object":"batch","status":"validating"}"""

        val result = parser.createBatch(
            listOf(BatchParseRequest("recipe-0", "# Toast"), BatchParseRequest("recipe-1", "# Jam")),
            "sk-openai",
            AiProviders.GPT_LUNA,
            thinkingEnabled = true
        )

        assertEquals(Outcome.Success("batch_1"), result)
        val (upload, create) = requests
        assertEquals("/v1/files", upload.url.encodedPath)
        assertEquals("Bearer sk-openai", upload.headers[HttpHeaders.Authorization])
        val form = upload.bodyText()
        assertTrue(form, form.contains("name=\"purpose\"") && form.contains("\r\n\r\nbatch\r\n"))
        assertTrue(form, form.contains("name=\"file\"; filename=\"batch.jsonl\""))
        val lines = form.lines().filter { it.startsWith("{") }.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(listOf("recipe-0", "recipe-1"), lines.map { it.getValue("custom_id").jsonPrimitive.content })
        val line = lines.first()
        assertEquals("POST", line.getValue("method").jsonPrimitive.content)
        assertEquals("/v1/responses", line.getValue("url").jsonPrimitive.content)
        val body = line.getValue("body").jsonObject
        assertEquals(AiProviders.GPT_LUNA, body.getValue("model").jsonPrimitive.content)
        assertEquals("medium", body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
        assertFalse("stream" in body)

        assertEquals("/v1/batches", create.url.encodedPath)
        val batch = create.json()
        assertEquals("file-in", batch.getValue("input_file_id").jsonPrimitive.content)
        assertEquals("/v1/responses", batch.getValue("endpoint").jsonPrimitive.content)
        assertEquals("24h", batch.getValue("completion_window").jsonPrimitive.content)
    }

    @Test
    fun `a failed upload doesn't create a batch`() = runTest {
        routes["POST /v1/files"] = HttpStatusCode.Unauthorized to """{"error":{"code":"invalid_api_key"}}"""

        val result = parser.createBatch(listOf(BatchParseRequest("recipe-0", "# Toast")), "sk-openai", AiProviders.GPT_SOL, false)

        assertEquals(Outcome.Failure(RecipeError.Ai(AiFailure.INVALID_API_KEY, 401, AiProviders.OPENAI_ID)), result)
        assertEquals(1, requests.size)
    }

    private fun batchJson(status: String, completed: Int = 0, failed: Int = 0, total: Int = 3, files: String = "") =
        """{"id":"batch_1","object":"batch","status":"$status",
           "request_counts":{"total":$total,"completed":$completed,"failed":$failed}$files}"""

    @Test
    fun `batch status maps the request counts`() = runTest {
        routes["GET /v1/batches/batch_1"] = HttpStatusCode.OK to batchJson("in_progress", completed = 1, failed = 1)
        assertEquals(
            Outcome.Success(BatchStatus(ended = false, succeeded = 1, processing = 1)),
            parser.getBatchStatus("batch_1", AiProviders.GPT_SOL, "sk-openai")
        )

        for (status in listOf("completed", "expired", "cancelled")) {
            routes["GET /v1/batches/batch_1"] = HttpStatusCode.OK to batchJson(status, completed = 3)
            assertEquals(
                Outcome.Success(BatchStatus(ended = true, succeeded = 3, processing = 0)),
                parser.getBatchStatus("batch_1", AiProviders.GPT_SOL, "sk-openai")
            )
        }

        routes["GET /v1/batches/batch_1"] = HttpStatusCode.OK to batchJson("failed", total = 0)
        assertEquals(
            Outcome.Failure(RecipeError.Ai(AiFailure.REQUEST_REJECTED, provider = AiProviders.OPENAI_ID)),
            parser.getBatchStatus("batch_1", AiProviders.GPT_SOL, "sk-openai")
        )

        routes["GET /v1/batches/batch_1"] = HttpStatusCode.TooManyRequests to """{"error":{"code":null}}"""
        assertEquals(
            Outcome.Failure(RecipeError.Ai(AiFailure.RATE_LIMITED, 429, AiProviders.OPENAI_ID)),
            parser.getBatchStatus("batch_1", AiProviders.GPT_SOL, "sk-openai")
        )
    }

    @Test
    fun `results are streamed from the output and error files, skipping handled ones`() = runTest {
        routes["GET /v1/batches/batch_1"] = HttpStatusCode.OK to batchJson(
            "expired",
            completed = 3,
            files = ""","output_file_id":"file-out","error_file_id":"file-err""""
        )
        fun line(customId: String, response: String) = """{"id":"r","custom_id":"$customId","response":{"status_code":200,"request_id":"x","body":$response},"error":null}"""
        routes["GET /v1/files/file-out/content"] = HttpStatusCode.OK to listOf(
            line("recipe-0", responseObject()),
            line("recipe-1", responseObject()),
            line("recipe-2", responseObject(content = """{"type":"output_text","text":"{\"success\":false,\"error\":\"a poem\",\"recipe\":null}"}"""))
        ).joinToString("\n", postfix = "\n")
        routes["GET /v1/files/file-err/content"] = HttpStatusCode.OK to listOf(
            """{"id":"r","custom_id":"recipe-3","response":null,"error":{"code":"batch_expired","message":"m"}}""",
            """{"id":"r","custom_id":"recipe-4","response":{"status_code":429,"body":{"error":{"code":"rate_limit_exceeded"}}},"error":null}"""
        ).joinToString("\n")

        val outcomes = mutableListOf<BatchItemOutcome>()
        val result = parser.forEachResult("batch_1", AiProviders.GPT_SOL, "sk-openai", skipCustomIds = setOf("recipe-1")) {
            outcomes += it
        }

        assertEquals(Outcome.Success(Unit), result)
        assertEquals(listOf("recipe-0", "recipe-2", "recipe-3", "recipe-4"), outcomes.map { it.customId })
        assertEquals("Toast", (outcomes[0] as BatchItemOutcome.Parsed).result.recipe.name)
        assertEquals(BatchItemOutcome.Failed("recipe-2", "Not a recipe: a poem"), outcomes[1])
        assertEquals(BatchItemOutcome.Failed("recipe-3", "The AI batch expired before this recipe was processed"), outcomes[2])
        assertEquals(BatchItemOutcome.Failed("recipe-4", "AI request failed: RATE_LIMITED (HTTP 429)"), outcomes[3])
        assertTrue(requests.all { it.headers[HttpHeaders.Authorization] == "Bearer sk-openai" })
    }

    @Test
    fun `cancel posts to the batch`() = runTest {
        routes["POST /v1/batches/batch_1/cancel"] = HttpStatusCode.OK to batchJson("cancelling")

        assertEquals(Outcome.Success(Unit), parser.cancelBatch("batch_1", AiProviders.GPT_SOL, "sk-openai"))
        assertEquals("/v1/batches/batch_1/cancel", requests.single().url.encodedPath)
    }
}
