package com.lionotter.recipes.data.ai

import android.util.Log
import com.anthropic.core.jsonMapper
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.messages.Message
import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.port.ParsedRecipeWithUsage
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RecipeResponseParserTest {

    private val parser = RecipeResponseParser(Json { ignoreUnknownKeys = true })

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun messageJson(stopReason: String, blocks: Array<out String>) =
        """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-4-8",
           "content":[${blocks.joinToString(",")}],"stop_reason":"$stopReason","stop_sequence":null,
           "usage":{"input_tokens":10,"output_tokens":20}}"""

    private fun betaMessage(stopReason: String, vararg blocks: String): BetaMessage =
        jsonMapper().readValue(messageJson(stopReason, blocks), BetaMessage::class.java)

    private fun message(stopReason: String, vararg blocks: String): Message =
        jsonMapper().readValue(messageJson(stopReason, blocks), Message::class.java)

    private fun textBlock(text: String): String =
        """{"type":"text","text":${JsonPrimitive(text)}}"""

    private val fallbackBlock =
        """{"type":"fallback","from":{"model":"claude-opus-5-5"},"to":{"model":"claude-opus-4-8"}}"""

    private val recipeJson = """{"success":true,"recipe":{"name":"Toast","instructionSections":[{"steps":[{"stepNumber":1,"instruction":"Toast the bread."}]}],"equipment":[],"tags":[]}}"""

    private fun Outcome<ParsedRecipeWithUsage>.value(): ParsedRecipeWithUsage =
        (this as? Outcome.Success)?.value ?: throw AssertionError("Expected success, got $this")

    private fun Outcome<ParsedRecipeWithUsage>.error(): RecipeError =
        (this as? Outcome.Failure)?.error ?: throw AssertionError("Expected failure, got $this")

    @Test
    fun `batch message is parsed with usage and the raw JSON`() {
        val result = parser.parseMessage(message("end_turn", textBlock(recipeJson))).value()
        assertEquals("Toast", result.recipe.name)
        assertEquals(10L, result.inputTokens)
        assertEquals(20L, result.outputTokens)
        assertEquals(recipeJson, result.aiOutputJson)
    }

    @Test
    fun `JSON inside a markdown code block is accepted`() {
        val result = parser.parseMessage(message("end_turn", textBlock("Here you go:\n```json\n$recipeJson\n```")))
        assertEquals("Toast", result.value().recipe.name)
    }

    @Test
    fun `beta message with pre-output fallback block parses`() {
        val result = parser.parseBetaMessage(betaMessage("end_turn", fallbackBlock, textBlock(recipeJson)))
        assertEquals("Toast", result.value().recipe.name)
    }

    @Test
    fun `mid-stream fallback continuation is joined with the partial text`() {
        val split = recipeJson.length / 2
        val result = parser.parseBetaMessage(
            betaMessage(
                "end_turn",
                textBlock(recipeJson.substring(0, split)),
                fallbackBlock,
                textBlock(recipeJson.substring(split))
            )
        )
        assertEquals("Toast", result.value().recipe.name)
    }

    @Test
    fun `mid-stream fallback that restarts the answer uses text after the boundary`() {
        val result = parser.parseBetaMessage(
            betaMessage("end_turn", textBlock("{\"success\":true,\"rec"), fallbackBlock, textBlock(recipeJson))
        )
        assertEquals("Toast", result.value().recipe.name)
    }

    @Test
    fun `refusal after fallback is reported as declined`() {
        val result = parser.parseBetaMessage(betaMessage("refusal", fallbackBlock))
        assertEquals(RecipeError.Ai(AiFailure.REFUSED), result.error())
    }

    @Test
    fun `truncated, empty and unreadable responses are unusable`() {
        assertEquals(
            RecipeError.Ai(AiFailure.TRUNCATED),
            parser.parseMessage(message("max_tokens", textBlock(recipeJson))).error()
        )
        assertEquals(RecipeError.Ai(AiFailure.EMPTY_RESPONSE), parser.parseMessage(message("end_turn")).error())
        assertEquals(
            RecipeError.Ai(AiFailure.UNREADABLE_RESPONSE),
            parser.parseMessage(message("end_turn", textBlock("{not json"))).error()
        )
    }

    @Test
    fun `a failure response carries the AI's explanation`() {
        val response = """{"success":false,"error":"This is a blog post","recipe":null}"""
        assertEquals(
            RecipeError.NotARecipe("This is a blog post"),
            parser.parseMessage(message("end_turn", textBlock(response))).error()
        )
    }

    @Test
    fun `success with only the placeholder recipe is rejected`() {
        val placeholder = """{"success":true,"error":"","recipe":{"name":"","story":null,"servings":null,""" +
            """"prepTime":null,"cookTime":null,"totalTime":null,"instructionSections":[],"equipment":[],"tags":[]}}"""
        val result = parser.parseBetaMessage(betaMessage("end_turn", textBlock(placeholder)))
        assertEquals(RecipeError.Ai(AiFailure.EMPTY_RESPONSE), result.error())
    }
}
