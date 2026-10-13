package com.lionotter.recipes.data.ai

import android.util.Log
import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse
import com.lionotter.recipes.domain.port.BatchItemOutcome
import com.lionotter.recipes.util.AppJson
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AnthropicRecipeParserTest {
    private val parser = AnthropicRecipeParser(AppJson, Dispatchers.Unconfined)

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun response(result: String): MessageBatchIndividualResponse =
        jsonMapper().readValue("""{"custom_id":"recipe-0","result":$result}""", MessageBatchIndividualResponse::class.java)

    private fun succeeded(text: String) = response(
        """{"type":"succeeded","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5-5",
           "content":[{"type":"text","text":${JsonPrimitive(text)}}],"stop_reason":"end_turn","stop_sequence":null,
           "usage":{"input_tokens":10,"output_tokens":20}}}"""
    )

    @Test
    fun `batch results become item outcomes`() {
        val recipeJson = """{"success":true,"recipe":{"name":"Toast","instructionSections":[{"steps":[{"stepNumber":1,"instruction":"Toast."}]}],"tags":[]}}"""
        val parsed = parser.toItemOutcome(succeeded(recipeJson))
        assertEquals("Toast", (parsed as BatchItemOutcome.Parsed).result.recipe.name)
        assertEquals("recipe-0", parsed.customId)

        assertEquals(
            BatchItemOutcome.Failed("recipe-0", "Not a recipe: a poem"),
            parser.toItemOutcome(succeeded("""{"success":false,"error":"a poem","recipe":null}"""))
        )
        assertEquals(
            BatchItemOutcome.Failed("recipe-0", "The AI request for this recipe failed"),
            parser.toItemOutcome(
                response("""{"type":"errored","error":{"type":"error","error":{"type":"api_error","message":"x"}}}""")
            )
        )
        assertEquals(
            BatchItemOutcome.Failed("recipe-0", "The AI batch expired before this recipe was processed"),
            parser.toItemOutcome(response("""{"type":"expired"}"""))
        )
        assertEquals(
            BatchItemOutcome.Failed("recipe-0", "The AI batch was canceled"),
            parser.toItemOutcome(response("""{"type":"canceled"}"""))
        )
    }
}
