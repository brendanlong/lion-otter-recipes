package com.lionotter.recipes.data.ai

import com.lionotter.recipes.domain.model.AiProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiKeyValidatorTest {

    @Test
    fun `valid key has no problem`() {
        assertNull(ApiKeyValidator.validate("sk-ant-valid-key-123", AiProviders.ANTHROPIC))
    }

    @Test
    fun `empty or blank key is Empty`() {
        assertEquals(ApiKeyProblem.Empty, ApiKeyValidator.validate("", AiProviders.ANTHROPIC))
        assertEquals(ApiKeyProblem.Empty, ApiKeyValidator.validate("   ", AiProviders.ANTHROPIC))
    }

    @Test
    fun `key without the provider's prefix is BadPrefix`() {
        assertEquals(ApiKeyProblem.BadPrefix, ApiKeyValidator.validate("invalid-key", AiProviders.ANTHROPIC))
        assertEquals(ApiKeyProblem.BadPrefix, ApiKeyValidator.validate("sk-proj-abc", AiProviders.ANTHROPIC))
    }

    @Test
    fun `whitespace is trimmed before validation`() {
        assertNull(ApiKeyValidator.validate("  sk-ant-valid-key  ", AiProviders.ANTHROPIC))
    }

    @Test
    fun `an Anthropic key is rejected where an OpenAI key belongs`() {
        assertEquals(
            ApiKeyProblem.OtherProvider(AiProviders.ANTHROPIC),
            ApiKeyValidator.validate("sk-ant-api03-abc", AiProviders.OPENAI)
        )
        assertNull(ApiKeyValidator.validate("sk-proj-abc", AiProviders.OPENAI))
    }
}
