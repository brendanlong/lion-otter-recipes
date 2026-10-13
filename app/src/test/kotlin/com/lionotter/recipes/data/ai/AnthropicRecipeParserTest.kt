package com.lionotter.recipes.data.ai

import android.util.Log
import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse
import com.lionotter.recipes.domain.port.BatchItemOutcome
import com.lionotter.recipes.domain.model.AiModels
import com.lionotter.recipes.domain.port.ParseAttachment
import com.lionotter.recipes.domain.port.ParseRequest
import com.lionotter.recipes.util.AppJson
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

class AnthropicRecipeParserTest {
    private val parser = AnthropicRecipeParser(AppJson, Dispatchers.Unconfined)

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun response(result: String): MessageBatchIndividualResponse =
        jsonMapper().readValue("""{"custom_id":"recipe-0","result":$result}""", MessageBatchIndividualResponse::class.java)

    private fun succeeded(text: String) = response(
        """{"type":"succeeded","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5-5",
           "content":[{"type":"text","text":${JsonPrimitive(text)}}],"stop_reason":"end_turn","stop_sequence":null,
           "usage":{"input_tokens":10,"output_tokens":20}}}"""
    )

    @Test
    fun `batch results become item outcomes`() {
        val recipeJson = """{"success":true,"recipe":{"name":"Toast","instructionSections":[{"steps":[{"stepNumber":1,"instruction":"Toast."}]}],"tags":[]}}"""
        val parsed = parser.toItemOutcome(succeeded(recipeJson))
        assertEquals("Toast", (parsed as BatchItemOutcome.Parsed).result.recipe.name)
        assertEquals("recipe-0", parsed.customId)

        assertEquals(
            BatchItemOutcome.Failed("recipe-0", "Not a recipe: a poem"),
            parser.toItemOutcome(succeeded("""{"success":false,"error":"a poem","recipe":null}"""))
        )
        assertEquals(
            BatchItemOutcome.Failed("recipe-0", "The AI request for this recipe failed"),
            parser.toItemOutcome(
                response("""{"type":"errored","error":{"type":"error","error":{"type":"api_error","message":"x"}}}""")
            )
        )
        assertEquals(
            BatchItemOutcome.Failed("recipe-0", "The AI batch expired before this recipe was processed"),
            parser.toItemOutcome(response("""{"type":"expired"}"""))
        )
        assertEquals(
            BatchItemOutcome.Failed("recipe-0", "The AI batch was canceled"),
            parser.toItemOutcome(response("""{"type":"canceled"}"""))
        )
    }

    private fun userContent(request: ParseRequest): JsonArray {
        val params = parser.buildParams(request, AiModels.MODEL_SONNET)
        val messages = Json.parseToJsonElement(jsonMapper().writeValueAsString(params.messages())).jsonArray
        val message = messages.single().jsonObject
        assertEquals("user", message["role"]?.jsonPrimitive?.content)
        return message["content"] as? JsonArray ?: JsonArray(listOf(JsonObject(mapOf(
            "type" to JsonPrimitive("text"), "text" to message["content"]!!
        ))))
    }

    private val JsonElement.type get() = jsonObject["type"]!!.jsonPrimitive.content

    @Test
    fun `a text request is a single text message`() {
        val content = userContent(ParseRequest("# Bread", "key", AiModels.MODEL_SONNET, thinkingEnabled = false))

        assertEquals(listOf("text"), content.map { it.type })
        assertTrue(content.single().jsonObject["text"]!!.jsonPrimitive.content.endsWith("# Bread"))
    }

    @Test
    fun `photos are sent as base64 image blocks in order, before the instructions`() {
        val first = byteArrayOf(1, 2, 3)
        val second = byteArrayOf(4, 5)
        val request = ParseRequest(
            content = "",
            apiKey = "key",
            model = AiModels.MODEL_SONNET,
            thinkingEnabled = false,
            existingTags = listOf("Baking"),
            attachments = listOf(ParseAttachment.Image(first, "image/jpeg"), ParseAttachment.Image(second, "image/png"))
        )

        val content = userContent(request)

        assertEquals(listOf("image", "image", "text"), content.map { it.type })
        val sources = content.take(2).map { it.jsonObject["source"]!!.jsonObject }
        assertEquals(listOf("base64", "base64"), sources.map { it["type"]!!.jsonPrimitive.content })
        assertEquals(listOf("image/jpeg", "image/png"), sources.map { it["media_type"]!!.jsonPrimitive.content })
        assertEquals(
            listOf(Base64.getEncoder().encodeToString(first), Base64.getEncoder().encodeToString(second)),
            sources.map { it["data"]!!.jsonPrimitive.content }
        )
        val text = content.last().jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text, text.contains("The 2 attached photos show one recipe"))
        assertTrue(text, text.contains("Baking"))
        assertTrue(text, !text.contains("Parse this recipe and extract the structured data:\n\n"))
    }

    @Test
    fun `a PDF is sent as a base64 document block`() {
        val pdf = "%PDF-1.4".toByteArray()
        val request = ParseRequest(
            content = "",
            apiKey = "key",
            model = AiModels.MODEL_SONNET,
            thinkingEnabled = true,
            attachments = listOf(ParseAttachment.Pdf(pdf))
        )

        val content = userContent(request)

        assertEquals(listOf("document", "text"), content.map { it.type })
        val source = content.first().jsonObject["source"]!!.jsonObject
        assertEquals("base64", source["type"]!!.jsonPrimitive.content)
        assertEquals("application/pdf", source["media_type"]!!.jsonPrimitive.content)
        assertEquals(Base64.getEncoder().encodeToString(pdf), source["data"]!!.jsonPrimitive.content)
        assertTrue(content.last().jsonObject["text"]!!.jsonPrimitive.content.contains("The attached PDF document contains a recipe"))
    }

    @Test
    fun `attachments don't change the cached system prompt`() {
        val withPhoto = parser.buildParams(
            ParseRequest("", "key", AiModels.MODEL_SONNET, false, attachments = listOf(ParseAttachment.Image(byteArrayOf(1), "image/jpeg"))),
            AiModels.MODEL_SONNET
        )
        val withText = parser.buildParams(ParseRequest("# Bread", "key", AiModels.MODEL_SONNET, false), AiModels.MODEL_SONNET)

        assertEquals(withText.system(), withPhoto.system())
    }
}
