package com.lionotter.recipes.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiModelsTest {

    @Test
    fun `defaults are supported models`() {
        assertTrue(AiModels.DEFAULT_MODEL in AiModels.SUPPORTED_MODELS)
        assertTrue(AiModels.DEFAULT_EDIT_MODEL in AiModels.SUPPORTED_MODELS)
    }

    @Test
    fun `normalizeModel keeps supported models`() {
        AiModels.SUPPORTED_MODELS.forEach {
            assertEquals(it, AiModels.normalizeModel(it))
        }
    }

    @Test
    fun `normalizeModel maps retired models to current family`() {
        assertEquals(AiModels.MODEL_OPUS, AiModels.normalizeModel("claude-opus-4-6"))
        assertEquals(AiModels.MODEL_SONNET, AiModels.normalizeModel("claude-sonnet-4-5"))
        assertEquals(AiModels.MODEL_HAIKU, AiModels.normalizeModel("claude-haiku-4-5-20251001"))
        assertEquals(AiModels.MODEL_HAIKU, AiModels.normalizeModel("claude-haiku-4-5"))
        assertEquals(AiModels.MODEL_FABLE, AiModels.normalizeModel("claude-fable-4"))
        assertEquals(AiModels.MODEL_HAIKU, AiModels.normalizeModel("gpt-4", AiModels.MODEL_HAIKU))
        assertEquals(AiModels.DEFAULT_MODEL, AiModels.normalizeModel(null))
        assertEquals(AiModels.DEFAULT_MODEL, AiModels.normalizeModel(" "))
    }
}
