package com.lionotter.recipes.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiKeyValidatorTest {

    @Test
    fun `valid key has no problem`() {
        assertNull(ApiKeyValidator.validate("sk-ant-valid-key-123"))
    }

    @Test
    fun `empty or blank key is EMPTY`() {
        assertEquals(ApiKeyProblem.EMPTY, ApiKeyValidator.validate(""))
        assertEquals(ApiKeyProblem.EMPTY, ApiKeyValidator.validate("   "))
    }

    @Test
    fun `key without the Anthropic prefix is BAD_PREFIX`() {
        assertEquals(ApiKeyProblem.BAD_PREFIX, ApiKeyValidator.validate("invalid-key"))
    }

    @Test
    fun `whitespace is trimmed before validation`() {
        assertNull(ApiKeyValidator.validate("  sk-ant-valid-key  "))
    }
}
