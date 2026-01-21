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
}
