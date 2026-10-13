package com.lionotter.recipes.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiKeyValidatorTest {

    @Test
    fun `valid key has no problem`() {
        assertNull(ApiKeyValidator.validate("sk-ant-valid-key-123", ApiKeyValidator.ANTHROPIC_API_KEY_PREFIX))
    }

    @Test
    fun `empty or blank key is EMPTY`() {
        assertEquals(ApiKeyProblem.EMPTY, ApiKeyValidator.validate("", ApiKeyValidator.ANTHROPIC_API_KEY_PREFIX))
        assertEquals(ApiKeyProblem.EMPTY, ApiKeyValidator.validate("   ", ApiKeyValidator.ANTHROPIC_API_KEY_PREFIX))
    }

    @Test
    fun `key without the Anthropic prefix is BAD_PREFIX`() {
        assertEquals(ApiKeyProblem.BAD_PREFIX, ApiKeyValidator.validate("invalid-key", ApiKeyValidator.ANTHROPIC_API_KEY_PREFIX))
    }

    @Test
    fun `whitespace is trimmed before validation`() {
        assertNull(ApiKeyValidator.validate("  sk-ant-valid-key  ", ApiKeyValidator.ANTHROPIC_API_KEY_PREFIX))
    }

    @Test
    fun `an Anthropic key is rejected where an OpenAI key belongs`() {
        assertEquals(
            ApiKeyProblem.ANTHROPIC_KEY,
            ApiKeyValidator.validate("sk-ant-api03-abc", ApiKeyValidator.OPENAI_API_KEY_PREFIX)
        )
        assertNull(ApiKeyValidator.validate("sk-proj-abc", ApiKeyValidator.OPENAI_API_KEY_PREFIX))
    }
}
