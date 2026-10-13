package com.lionotter.recipes.data.ai

import com.anthropic.models.messages.OutputConfig
import com.lionotter.recipes.domain.model.AiModels
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AnthropicImagePromptWriterTest {
    @Suppress("InjectDispatcher") // buildParams doesn't use it
    private val writer = AnthropicImagePromptWriter(Dispatchers.Unconfined)

    @Test
    fun `asks the model for just a prompt, with low effort and no thinking`() {
        for (model in AiModels.SUPPORTED_MODELS) {
            val params = writer.buildParams("# Banana Bread", model)
            assertEquals(ImagePrompts.writerSystemPrompt, params.system().get().asString())
            assertEquals(OutputConfig.Effort.LOW, params.outputConfig().get().effort().get())
            assertFalse(params.thinking().isPresent)
        }
    }
}
