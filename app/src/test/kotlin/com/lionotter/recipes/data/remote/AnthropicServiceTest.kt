package com.lionotter.recipes.data.remote

import android.util.Log
import com.anthropic.core.jsonMapper
import com.anthropic.models.beta.messages.BetaMessage
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicServiceTest {

    @Test
    fun `validateApiKey returns null for valid key`() {
        assertNull(AnthropicService.validateApiKey("sk-ant-valid-key-123"))
    }

    @Test
    fun `validateApiKey returns error for empty key`() {
        assertEquals("API key cannot be empty", AnthropicService.validateApiKey(""))
    }

    @Test
    fun `validateApiKey returns error for blank key`() {
        assertEquals("API key cannot be empty", AnthropicService.validateApiKey("   "))
    }

    @Test
    fun `validateApiKey returns error for invalid prefix`() {
        val error = AnthropicService.validateApiKey("invalid-key")
        assertTrue(error?.contains("sk-ant-") == true)
    }

    @Test
    fun `validateApiKey trims whitespace before validation`() {
        assertNull(AnthropicService.validateApiKey("  sk-ant-valid-key  "))
    }

    @Test
    fun `isValidApiKey returns true for valid key`() {
        assertTrue(AnthropicService.isValidApiKey("sk-ant-valid-key-123"))
    }

    @Test
    fun `isValidApiKey returns false for empty key`() {
        assertFalse(AnthropicService.isValidApiKey(""))
    }

    @Test
    fun `isValidApiKey returns false for invalid prefix`() {
        assertFalse(AnthropicService.isValidApiKey("invalid-key"))
    }

    @Test
    fun `DEFAULT_MODEL is set`() {
        assertTrue(AnthropicService.DEFAULT_MODEL.isNotEmpty())
    }

    @Test
    fun `buildSystemPrompt without overrides includes default densities`() {
        val prompt = AnthropicService.buildSystemPrompt()
        assertTrue(prompt.contains("all-purpose flour 0.51"))
        assertTrue(prompt.contains("granulated sugar 0.84"))
        assertTrue(prompt.contains("butter 0.96"))
        assertFalse(prompt.contains("%DENSITY_TABLE%"))
    }

    @Test
    fun `buildSystemPrompt is stable across calls for prompt caching`() {
        assertEquals(AnthropicService.buildSystemPrompt(), AnthropicService.buildSystemPrompt())
    }

    @Test
    fun `buildUserMessage includes density overrides before content`() {
        val message = AnthropicService.buildUserMessage("RECIPE TEXT", mapOf("custom ingredient" to 1.5))
        assertTrue(message.contains("custom ingredient 1.5"))
        assertTrue(message.indexOf("custom ingredient 1.5") < message.indexOf("RECIPE TEXT"))
    }

    @Test
    fun `buildUserMessage includes user instructions`() {
        val message = AnthropicService.buildUserMessage("RECIPE TEXT", aiInstructions = "make it vegan")
        assertTrue(message.contains("make it vegan"))
        assertFalse(message.contains("densities"))
    }

    @Test
    fun `normalizeModel keeps supported models`() {
        AnthropicService.SUPPORTED_MODELS.forEach {
            assertEquals(it, AnthropicService.normalizeModel(it))
        }
    }

    @Test
    fun `normalizeModel maps retired models to current family`() {
        assertEquals(AnthropicService.MODEL_OPUS, AnthropicService.normalizeModel("claude-opus-4-6"))
        assertEquals(AnthropicService.MODEL_SONNET, AnthropicService.normalizeModel("claude-sonnet-4-5"))
        assertEquals(AnthropicService.MODEL_HAIKU, AnthropicService.normalizeModel("claude-haiku-4-5-20251001"))
        assertEquals(AnthropicService.MODEL_SONNET, AnthropicService.normalizeModel("gpt-4", AnthropicService.MODEL_SONNET))
        assertEquals(AnthropicService.DEFAULT_MODEL, AnthropicService.normalizeModel(null))
    }

    @Test
    fun `haiku uses budget thinking only when enabled and no effort`() {
        val on = AnthropicService.requestConfigFor(AnthropicService.MODEL_HAIKU, true)
        assertTrue(on.thinkingBudgetTokens != null)
        assertFalse(on.adaptiveThinking)
        assertFalse(on.toOutputConfig().effort().isPresent)
        assertFalse(on.toBetaOutputConfig().effort().isPresent)
        val off = AnthropicService.requestConfigFor(AnthropicService.MODEL_HAIKU, false)
        assertNull(off.thinkingBudgetTokens)
    }

    @Test
    fun `adaptive models never send budget thinking`() {
        listOf(AnthropicService.MODEL_OPUS, AnthropicService.MODEL_SONNET, AnthropicService.MODEL_FABLE).forEach { model ->
            listOf(true, false).forEach { enabled ->
                val config = AnthropicService.requestConfigFor(model, enabled)
                assertNull(config.thinkingBudgetTokens)
                assertTrue(config.toOutputConfig().effort().isPresent)
                assertTrue(config.toOutputConfig().format().isPresent)
                assertTrue(config.toBetaOutputConfig().effort().isPresent)
                assertTrue(config.toBetaOutputConfig().format().isPresent)
            }
        }
        assertFalse(AnthropicService.requestConfigFor(AnthropicService.MODEL_FABLE, true).adaptiveThinking)
        assertTrue(AnthropicService.requestConfigFor(AnthropicService.MODEL_OPUS, true).adaptiveThinking)
    }

    private val service = AnthropicService(Json { ignoreUnknownKeys = true })

    private fun betaMessage(stopReason: String, vararg blocks: String): BetaMessage =
        jsonMapper().readValue(
            """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-4-8",
               "content":[${blocks.joinToString(",")}],"stop_reason":"$stopReason","stop_sequence":null,
               "usage":{"input_tokens":10,"output_tokens":20}}""",
            BetaMessage::class.java
        )

    private fun textBlock(text: String): String =
        """{"type":"text","text":${JsonPrimitive(text)}}"""

    private val fallbackBlock =
        """{"type":"fallback","from":{"model":"claude-opus-5-5"},"to":{"model":"claude-opus-4-8"}}"""

    private val recipeJson = """{"success":true,"recipe":{"name":"Toast","instructionSections":[],"equipment":[],"tags":[]}}"""

    @Test
    fun `beta message with pre-output fallback block parses`() = withMockedLog {
        val result = service.parseBetaMessageResult(betaMessage("end_turn", fallbackBlock, textBlock(recipeJson)))
        assertEquals("Toast", result.getOrThrow().result.name)
    }

    @Test
    fun `mid-stream fallback continuation is joined with the partial text`() = withMockedLog {
        val split = recipeJson.length / 2
        val result = service.parseBetaMessageResult(
            betaMessage(
                "end_turn",
                textBlock(recipeJson.substring(0, split)),
                fallbackBlock,
                textBlock(recipeJson.substring(split))
            )
        )
        assertEquals("Toast", result.getOrThrow().result.name)
    }

    @Test
    fun `mid-stream fallback that restarts the answer uses text after the boundary`() = withMockedLog {
        val result = service.parseBetaMessageResult(
            betaMessage("end_turn", textBlock("{\"success\":true,\"rec"), fallbackBlock, textBlock(recipeJson))
        )
        assertEquals("Toast", result.getOrThrow().result.name)
    }

    @Test
    fun `refusal after fallback is reported as declined`() = withMockedLog {
        val result = service.parseBetaMessageResult(betaMessage("refusal", fallbackBlock))
        assertEquals("The AI declined to process this content.", result.exceptionOrNull()?.message)
    }

    private fun withMockedLog(block: () -> Unit) {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        try {
            block()
        } finally {
            unmockkStatic(Log::class)
        }
    }

    @Test
    fun `friendly exception passes through recipe parse errors`() {
        val e = RecipeParseException("not a recipe")
        assertEquals(e, AnthropicService.toFriendlyException(e))
    }

    @Test
    fun `friendly exception marks network errors transient`() {
        val e = AnthropicService.toFriendlyException(java.io.IOException("boom"))
        assertTrue(e is AnthropicRequestException && e.isTransient)
    }
}
