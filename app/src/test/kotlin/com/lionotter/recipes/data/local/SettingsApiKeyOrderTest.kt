package com.lionotter.recipes.data.local

import com.lionotter.recipes.domain.model.AiProviders.ANTHROPIC_ID
import com.lionotter.recipes.domain.model.AiProviders.OPENAI_ID
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsApiKeyOrderTest {
    private val keys = linkedMapOf(ANTHROPIC_ID to "a", OPENAI_ID to "o")

    private fun order(storedOrder: List<String>) = SettingsDataStore.orderedApiKeys(keys, storedOrder).keys.toList()

    @Test
    fun `keys follow the stored order`() {
        assertEquals(listOf(OPENAI_ID, ANTHROPIC_ID), order(listOf(OPENAI_ID, ANTHROPIC_ID)))
    }

    @Test
    fun `keys stored before the order was kept come in catalog order`() {
        assertEquals(listOf(ANTHROPIC_ID, OPENAI_ID), order(emptyList()))
    }

    @Test
    fun `providers without a key are dropped from the order`() {
        assertEquals(
            listOf(ANTHROPIC_ID),
            SettingsDataStore.orderedApiKeys(mapOf(ANTHROPIC_ID to "a"), listOf(OPENAI_ID, ANTHROPIC_ID)).keys.toList()
        )
    }
}
