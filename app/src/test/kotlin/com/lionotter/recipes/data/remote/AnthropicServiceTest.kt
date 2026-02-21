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
    fun `buildSystemPrompt with overrides includes merged densities`() {
        val overrides = mapOf("custom ingredient" to 1.5)
        val prompt = AnthropicService.buildSystemPrompt(overrides)
        // Default densities still present
        assertTrue(prompt.contains("all-purpose flour 0.51"))
        // Custom density added
        assertTrue(prompt.contains("custom ingredient 1.5"))
    }

    @Test
    fun `buildSystemPrompt overrides take precedence over defaults`() {
        val overrides = mapOf("butter" to 0.99)
        val prompt = AnthropicService.buildSystemPrompt(overrides)
        // Override value should be present
        assertTrue(prompt.contains("butter 0.99"))
        // Original value should NOT be present
        assertFalse(prompt.contains("butter 0.96"))
    }
}
