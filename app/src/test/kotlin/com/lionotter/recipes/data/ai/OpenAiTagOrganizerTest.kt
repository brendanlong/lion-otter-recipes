package com.lionotter.recipes.data.ai

import android.util.Log
import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.model.AiProviders
import com.lionotter.recipes.domain.model.TagMapping
import com.lionotter.recipes.domain.model.TagUsage
import com.lionotter.recipes.domain.port.TagCleanupRequest
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAiTagOrganizerTest {
    private val requests = mutableListOf<HttpRequestData>()
    private var response: Pair<HttpStatusCode, String> = HttpStatusCode.OK to ""

    private val client = HttpClient(MockEngine) {
        install(HttpTimeout)
        engine {
            addHandler { request ->
                requests += request
                respond(response.second, response.first, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }
        }
    }
    private val organizer = OpenAiTagOrganizer(AppJson, OpenAiResponses(client, AppJson))
    private val tags = listOf(TagUsage("Dessert", 3), TagUsage("desserts", 1))

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

    private fun completed(content: String, status: String = "completed", incompleteReason: String? = null): String {
        val response = """{"id":"resp_1","status":"$status",""" +
            """"incomplete_details":${incompleteReason?.let { """{"reason":"$it"}""" } ?: "null"},""" +
            """"output":[{"type":"message","role":"assistant","content":[$content]}],""" +
            """"usage":{"input_tokens":5,"output_tokens":6}}"""
        return "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":$response}\n\n"
    }

    private fun text(value: String) = """{"type":"output_text","text":${JsonPrimitive(value)}}"""

    @Test
    fun `request uses the model, the tag prompt and the strict schema`() {
        val body = organizer.buildBody(TagCleanupRequest(tags, "sk-openai", AiProviders.GPT_LUNA, false), AiProviders.GPT_LUNA)

        assertEquals(AiProviders.GPT_LUNA, body.getValue("model").jsonPrimitive.content)
        assertEquals(TagCleanupPrompt.systemPrompt, body.getValue("instructions").jsonPrimitive.content)
        val text = body.getValue("input").jsonArray.single().jsonObject.getValue("content").jsonArray.single().jsonObject
        assertEquals(TagCleanupPrompt.buildUserMessage(tags), text.getValue("text").jsonPrimitive.content)
        val format = body.getValue("text").jsonObject.getValue("format").jsonObject
        assertEquals("tag_changes", format.getValue("name").jsonPrimitive.content)
        assertTrue(format.getValue("strict").jsonPrimitive.boolean)
        assertEquals(OpenAiResponses.toJson(TagCleanupPrompt.RESPONSE_SCHEMA), format.getValue("schema"))
        assertEquals("low", body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
        assertEquals(16_000L, body.getValue("max_output_tokens").jsonPrimitive.long)
    }

    @Test
    fun `streams the request and returns the suggested mappings`() = runTest {
        response = HttpStatusCode.OK to completed(text("""{"changes":[{"from":"desserts","to":"Dessert"},{"from":"recipe","to":null}]}"""))

        val result = organizer.suggestTagChanges(TagCleanupRequest(tags, "sk-openai", "unknown-model", true))

        assertEquals(Outcome.Success(listOf(TagMapping("desserts", "Dessert"), TagMapping("recipe", null))), result)
        val body = Json.parseToJsonElement(requests.single().body.toByteArray().decodeToString()).jsonObject
        assertEquals(AiProviders.GPT_LUNA, body.getValue("model").jsonPrimitive.content)
        assertEquals("medium", body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
        assertTrue(body.getValue("stream").jsonPrimitive.boolean)
        assertEquals("Bearer sk-openai", requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `unusable responses are AI failures`() = runTest {
        val request = TagCleanupRequest(tags, "sk-openai", AiProviders.GPT_LUNA, false)
        val cases = listOf(
            completed("""{"type":"refusal","refusal":"No."}""") to AiFailure.REFUSED,
            completed(text("{\"chan"), status = "incomplete", incompleteReason = "max_output_tokens") to AiFailure.TRUNCATED,
            completed(text(" ")) to AiFailure.EMPTY_RESPONSE,
            completed(text("not json")) to AiFailure.UNREADABLE_RESPONSE
        )
        for ((stream, failure) in cases) {
            response = HttpStatusCode.OK to stream
            assertEquals(Outcome.Failure(RecipeError.Ai(failure, provider = AiProviders.OPENAI_ID)), organizer.suggestTagChanges(request))
        }

        response = HttpStatusCode.Unauthorized to """{"error":{"code":"invalid_api_key"}}"""
        assertEquals(
            Outcome.Failure(RecipeError.Ai(AiFailure.INVALID_API_KEY, 401, AiProviders.OPENAI_ID)),
            organizer.suggestTagChanges(request)
        )
    }
}
