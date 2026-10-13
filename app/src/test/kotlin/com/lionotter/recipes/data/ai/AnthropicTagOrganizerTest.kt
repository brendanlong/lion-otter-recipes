package com.lionotter.recipes.data.ai

import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.model.AiModels
import com.lionotter.recipes.domain.model.TagMapping
import com.lionotter.recipes.domain.model.TagUsage
import com.lionotter.recipes.domain.port.TagCleanupRequest
import com.lionotter.recipes.util.AppJson
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicTagOrganizerTest {
    private val organizer = AnthropicTagOrganizer(AppJson, StandardTestDispatcher())
    private val tags = listOf(TagUsage("Dessert", 3), TagUsage("desserts", 1))

    @Test
    fun `user message lists every tag with its recipe count`() {
        val message = TagCleanupPrompt.buildUserMessage(tags)
        assertTrue(message.contains("3\tDessert\n"))
        assertTrue(message.contains("1\tdesserts\n"))
    }

    @Test
    fun `request uses the model, the structured output schema and no thinking unless enabled`() {
        val params = organizer.buildParams(TagCleanupRequest(tags, "sk-ant-test", AiModels.MODEL_HAIKU, false), AiModels.MODEL_HAIKU)
        assertEquals(AiModels.MODEL_HAIKU, params.model().asString())
        assertTrue(params.outputConfig().get().format().isPresent)
        assertFalse(params.thinking().isPresent)
        assertTrue(params.system().get().asString().startsWith("You tidy up the tags"))

        val thinking = organizer.buildParams(TagCleanupRequest(tags, "sk-ant-test", AiModels.MODEL_HAIKU, true), AiModels.MODEL_HAIKU)
        assertTrue(thinking.thinking().isPresent)
    }

    @Test
    fun `parses the suggested mappings`() {
        val text = """{"changes":[{"from":"desserts","to":"Dessert"},{"from":"recipe","to":null}]}"""
        assertEquals(
            Outcome.Success(listOf(TagMapping("desserts", "Dessert"), TagMapping("recipe", null))),
            organizer.parseResponseText(text)
        )
        assertEquals(Outcome.Success(emptyList<TagMapping>()), organizer.parseResponseText("""{"changes":[]}"""))
    }

    @Test
    fun `unusable responses are AI failures`() {
        assertEquals(Outcome.Failure(RecipeError.Ai(AiFailure.EMPTY_RESPONSE)), organizer.parseResponseText(" "))
        assertEquals(Outcome.Failure(RecipeError.Ai(AiFailure.UNREADABLE_RESPONSE)), organizer.parseResponseText("not json"))
    }
}
