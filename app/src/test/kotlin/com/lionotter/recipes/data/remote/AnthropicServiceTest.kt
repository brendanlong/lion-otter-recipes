package com.lionotter.recipes.data.remote

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
        assertTrue(on.thinking?.isEnabled() == true)
        assertFalse(on.outputConfig.effort().isPresent)
        val off = AnthropicService.requestConfigFor(AnthropicService.MODEL_HAIKU, false)
        assertNull(off.thinking)
    }

    @Test
    fun `adaptive models never send budget or disabled thinking`() {
        listOf(AnthropicService.MODEL_OPUS, AnthropicService.MODEL_SONNET, AnthropicService.MODEL_FABLE).forEach { model ->
            listOf(true, false).forEach { enabled ->
                val config = AnthropicService.requestConfigFor(model, enabled)
                assertFalse(config.thinking?.isEnabled() == true)
                assertFalse(config.thinking?.isDisabled() == true)
                assertTrue(config.outputConfig.effort().isPresent)
                assertTrue(config.outputConfig.format().isPresent)
            }
        }
        assertNull(AnthropicService.requestConfigFor(AnthropicService.MODEL_FABLE, true).thinking)
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
