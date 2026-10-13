package com.lionotter.recipes.data.ai

import android.util.Log
import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Base64

class OpenAiImageGeneratorTest {
    private val requests = mutableListOf<HttpRequestData>()
    private var response: Pair<HttpStatusCode, String> = HttpStatusCode.OK to imageJson(byteArrayOf(1, 2, 3))

    private val client = HttpClient(MockEngine) {
        install(HttpTimeout)
        engine {
            addHandler { request ->
                requests += request
                respond(
                    response.second,
                    response.first,
                    headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        }
    }
    private val generator = OpenAiImageGenerator(client, AppJson)

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

    private fun imageJson(bytes: ByteArray) =
        """{"created":1,"data":[{"b64_json":"${Base64.getEncoder().encodeToString(bytes)}"}]}"""

    @Test
    fun `sends the prompt with the key and returns the decoded image`() = runTest {
        val result = generator.generate("A loaf of banana bread", "sk-openai")

        assertArrayEquals(byteArrayOf(1, 2, 3), (result as Outcome.Success).value)
        val request = requests.single()
        assertEquals("https://api.openai.com/v1/images/generations", request.url.toString())
        assertEquals("Bearer sk-openai", request.headers[HttpHeaders.Authorization])
        val body = Json.decodeFromString<JsonObject>(request.body.toByteArray().decodeToString())
        assertEquals("A loaf of banana bread", body.getValue("prompt").jsonPrimitive.content)
        assertEquals(OpenAiImageGenerator.MODEL, body.getValue("model").jsonPrimitive.content)
        assertEquals("low", body.getValue("quality").jsonPrimitive.content)
        assertEquals("jpeg", body.getValue("output_format").jsonPrimitive.content)
    }

    @Test
    fun `HTTP errors map to AI failures`() = runTest {
        fun error(code: String?) = """{"error":{"message":"m","type":"t","code":${code?.let { "\"$it\"" }}}}"""
        val cases = listOf(
            HttpStatusCode.Unauthorized to error(null) to AiFailure.INVALID_API_KEY,
            HttpStatusCode.Forbidden to error(null) to AiFailure.PERMISSION_DENIED,
            HttpStatusCode.TooManyRequests to error(null) to AiFailure.RATE_LIMITED,
            HttpStatusCode.TooManyRequests to error("insufficient_quota") to AiFailure.OUT_OF_CREDITS,
            HttpStatusCode.BadRequest to error("moderation_blocked") to AiFailure.REFUSED,
            HttpStatusCode.BadRequest to "not json" to AiFailure.REQUEST_REJECTED,
            HttpStatusCode.ServiceUnavailable to "" to AiFailure.OVERLOADED
        )
        for ((statusAndBody, failure) in cases) {
            response = statusAndBody
            assertEquals(
                Outcome.Failure(RecipeError.Ai(failure, statusAndBody.first.value)),
                generator.generate("prompt", "sk-openai")
            )
        }
    }

    @Test
    fun `a response without an image fails`() = runTest {
        response = HttpStatusCode.OK to """{"created":1,"data":[]}"""
        assertEquals(Outcome.Failure(RecipeError.Ai(AiFailure.EMPTY_RESPONSE)), generator.generate("prompt", "key"))

        response = HttpStatusCode.OK to "<html>"
        assertEquals(Outcome.Failure(RecipeError.Ai(AiFailure.UNREADABLE_RESPONSE)), generator.generate("prompt", "key"))
    }
}
