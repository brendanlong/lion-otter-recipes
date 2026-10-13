package com.lionotter.recipes.domain.model

import com.lionotter.recipes.domain.model.AiProviders.ANTHROPIC_ID
import com.lionotter.recipes.domain.model.AiProviders.OPENAI_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiProvidersTest {
    private val anthropic = ANTHROPIC_ID to "sk-ant-key"
    private val openAi = OPENAI_ID to "sk-proj-key"

    private fun resolve(role: ModelRole, selected: String?, vararg keys: Pair<String, String>) =
        AiProviders.resolve(role, selected, linkedMapOf(*keys))?.model?.id

    private fun defaults(vararg keys: Pair<String, String>) = ModelRole.entries.map { resolve(it, null, *keys) }

    @Test
    fun `every provider default is one of its models of the right kind`() {
        for (provider in AiProviders.ALL) {
            for (role in ModelRole.entries) {
                val model = provider.defaultModel(role) ?: continue
                assertEquals(role.kind, model.kind)
                assertEquals(provider.id, model.providerId)
            }
        }
    }

    @Test
    fun `models default to the first configured provider that has a model for the role`() {
        assertEquals(
            listOf(AiProviders.CLAUDE_SONNET, AiProviders.CLAUDE_HAIKU, AiProviders.GPT_IMAGE_FLARE),
            defaults(anthropic, openAi)
        )
        assertEquals(
            listOf(AiProviders.GPT_SOL, AiProviders.GPT_LUNA, AiProviders.GPT_IMAGE_FLARE),
            defaults(openAi, anthropic)
        )
        assertEquals(listOf(AiProviders.CLAUDE_SONNET, AiProviders.CLAUDE_HAIKU, null), defaults(anthropic))
        assertEquals(listOf(null, null, null), defaults())
    }

    @Test
    fun `a selected model is used only while its provider has a key`() {
        assertEquals(AiProviders.GPT_ASTRA, resolve(ModelRole.IMPORT, AiProviders.GPT_ASTRA, anthropic, openAi))
        assertEquals(AiProviders.CLAUDE_SONNET, resolve(ModelRole.IMPORT, AiProviders.GPT_ASTRA, anthropic))
        assertNull(resolve(ModelRole.IMAGE, AiProviders.GPT_IMAGE_SUNBURST, anthropic))
    }

    @Test
    fun `a selected model of the wrong kind is ignored`() {
        assertEquals(AiProviders.GPT_SOL, resolve(ModelRole.IMPORT, AiProviders.GPT_IMAGE_FLARE, openAi))
        assertEquals(AiProviders.GPT_IMAGE_FLARE, resolve(ModelRole.IMAGE, AiProviders.GPT_SOL, openAi))
    }

    @Test
    fun `retired model IDs map to the current model of the family`() {
        assertEquals(AiProviders.CLAUDE_OPUS, AiProviders.findModel("claude-opus-4-6")?.id)
        assertEquals(AiProviders.CLAUDE_SONNET, AiProviders.findModel("claude-sonnet-4-5")?.id)
        assertEquals(AiProviders.CLAUDE_HAIKU, AiProviders.findModel("claude-haiku-4-5-20251001")?.id)
        assertEquals(AiProviders.CLAUDE_FABLE, AiProviders.findModel("claude-fable-4")?.id)
        assertEquals(AiProviders.GPT_SOL, AiProviders.findModel("gpt-6-sol")?.id)
        assertNull(AiProviders.findModel("gpt-4"))
        assertNull(AiProviders.findModel(" "))
        assertEquals(AiProviders.CLAUDE_HAIKU, resolve(ModelRole.IMPORT, "claude-haiku-4-5", anthropic))
    }

    @Test
    fun `unknown selections fall back to the default`() {
        assertEquals(AiProviders.CLAUDE_SONNET, resolve(ModelRole.IMPORT, "gpt-4", anthropic))
    }

    @Test
    fun `available models follow the order the keys were added`() {
        val models = AiProviders.availableModels(ModelKind.TEXT, linkedMapOf(openAi, anthropic)).map { it.id }
        assertEquals(
            listOf(
                AiProviders.GPT_ASTRA, AiProviders.GPT_SOL, AiProviders.GPT_LUNA,
                AiProviders.CLAUDE_FABLE, AiProviders.CLAUDE_OPUS, AiProviders.CLAUDE_SONNET, AiProviders.CLAUDE_HAIKU
            ),
            models
        )
        assertEquals(emptyList<AiModel>(), AiProviders.availableModels(ModelKind.IMAGE, linkedMapOf(anthropic)))
    }

    @Test
    fun `configuredModel pairs a model with its provider's key`() {
        val keys = linkedMapOf(anthropic, openAi)
        assertEquals("sk-proj-key", AiProviders.configuredModel(AiProviders.GPT_LUNA, keys)?.apiKey)
        assertEquals("sk-ant-key", AiProviders.configuredModel(AiProviders.CLAUDE_OPUS, keys)?.apiKey)
        assertNull(AiProviders.configuredModel(AiProviders.GPT_LUNA, linkedMapOf(anthropic)))
    }
}
