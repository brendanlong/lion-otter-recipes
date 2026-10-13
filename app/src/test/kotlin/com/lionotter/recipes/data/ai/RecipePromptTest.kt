package com.lionotter.recipes.data.ai

import com.lionotter.recipes.domain.model.AiModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipePromptTest {

    @Test
    fun `system prompt includes the default density table`() {
        val prompt = RecipePrompt.systemPrompt
        assertTrue(prompt.contains("all-purpose flour 0.51"))
        assertTrue(prompt.contains("granulated sugar 0.84"))
        assertTrue(prompt.contains("butter 0.96"))
        assertFalse(prompt.contains("%DENSITY_TABLE%"))
    }

    /**
     * The prompt is cached server-side and tuned by hand; changing it should be deliberate.
     * Update the hash when you change the prompt or the default density table on purpose.
     */
    @Test
    fun `system prompt is pinned`() {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(RecipePrompt.systemPrompt.toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(SYSTEM_PROMPT_SHA256, digest)
    }

    @Test
    fun `system blocks are cacheable and identical for both APIs`() {
        val block = RecipeRequests.systemBlocks.single()
        val betaBlock = RecipeRequests.betaSystemBlocks.single()
        assertEquals(RecipePrompt.systemPrompt, block.text())
        assertEquals(RecipePrompt.systemPrompt, betaBlock.text())
        assertTrue(block.cacheControl().isPresent)
        assertTrue(betaBlock.cacheControl().isPresent)
    }

    @Test
    fun `user message puts density overrides before the content`() {
        val message = RecipePrompt.buildUserMessage("RECIPE TEXT", mapOf("custom ingredient" to 1.5))
        assertTrue(message.contains("custom ingredient 1.5"))
        assertTrue(message.indexOf("custom ingredient 1.5") < message.indexOf("RECIPE TEXT"))
    }

    @Test
    fun `the parse and the prompt writer share the image prompt guidelines`() {
        assertTrue(RecipePrompt.systemPrompt.contains(ImagePrompts.GUIDELINES))
        assertTrue(ImagePrompts.writerSystemPrompt.contains(ImagePrompts.GUIDELINES))
    }

    @Test
    fun `user message includes user instructions`() {
        val message = RecipePrompt.buildUserMessage("RECIPE TEXT", aiInstructions = "make it vegan")
        assertTrue(message.contains("make it vegan"))
        assertFalse(message.contains("densities"))
    }

    @Test
    fun `user message lists the existing tags before the content, and the system prompt doesn't`() {
        val message = RecipePrompt.buildUserMessage("RECIPE TEXT", existingTags = listOf("Dessert", "Quick & easy"))
        assertTrue(message.contains("\nDessert\nQuick & easy\n"))
        assertTrue(message.indexOf("Dessert") < message.indexOf("RECIPE TEXT"))
        assertFalse(RecipePrompt.systemPrompt.contains("Quick & easy"))
        assertFalse(RecipePrompt.buildUserMessage("RECIPE TEXT").contains("already uses"))
    }

    @Test
    fun `every supported model gets effort and structured output`() {
        AiModels.SUPPORTED_MODELS.forEach { model ->
            listOf(true, false).forEach { enabled ->
                val config = RecipeRequests.requestConfigFor(model, enabled)
                assertTrue(config.toOutputConfig().effort().isPresent)
                assertTrue(config.toOutputConfig().format().isPresent)
                assertTrue(config.toBetaOutputConfig().effort().isPresent)
                assertTrue(config.toBetaOutputConfig().format().isPresent)
            }
            assertTrue(RecipeRequests.supportsServerSideFallback(model))
        }
    }

    @Test
    fun `thinking maps to effort, and Fable gets no explicit thinking config`() {
        assertEquals(Effort.MEDIUM, RecipeRequests.requestConfigFor(AiModels.MODEL_HAIKU, true).effort)
        assertEquals(Effort.LOW, RecipeRequests.requestConfigFor(AiModels.MODEL_HAIKU, false).effort)
        assertFalse(RecipeRequests.requestConfigFor(AiModels.MODEL_FABLE, true).adaptiveThinking)
        assertTrue(RecipeRequests.requestConfigFor(AiModels.MODEL_OPUS, true).adaptiveThinking)
        assertTrue(RecipeRequests.requestConfigFor(AiModels.MODEL_HAIKU, true).adaptiveThinking)
        assertFalse(RecipeRequests.requestConfigFor(AiModels.MODEL_HAIKU, false).adaptiveThinking)
    }

    private companion object {
        const val SYSTEM_PROMPT_SHA256 = "b74e10119e84b8a18c2a4f2e5d7284b5591b77b31815e386d863d34cae6f26a5"
    }
}
