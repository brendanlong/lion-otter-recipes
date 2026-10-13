package com.lionotter.recipes.live

import com.lionotter.recipes.data.remote.AnthropicService
import com.lionotter.recipes.util.AppJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Opt-in end-to-end check of the real Anthropic API (request shape, structured-output schema,
 * response parsing). Skipped unless LIVE_ANTHROPIC_KEY is set:
 * `LIVE_ANTHROPIC_KEY=sk-ant-... ./gradlew :app:testDebugUnitTest --tests '*LiveAnthropicTest*'`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LiveAnthropicTest {
    private val html = """
        <html><body><h1>Simple Banana Bread</h1><p>Serves 8. Prep 15 min, bake 60 min.</p>
        <h2>Ingredients</h2><ul><li>3 ripe bananas</li><li>1/3 cup melted butter</li><li>3/4 cup sugar</li>
        <li>1 egg</li><li>1 tsp vanilla</li><li>1 tsp baking soda</li><li>pinch of salt</li><li>1 1/2 cups flour</li></ul>
        <h2>Steps</h2><ol><li>Heat oven to 350F. Mash bananas with butter.</li><li>Mix in sugar, egg, vanilla.</li>
        <li>Stir in baking soda, salt, then flour.</li><li>Bake in a loaf pan 60 minutes.</li></ol></body></html>
    """.trimIndent()

    @Test
    fun parse() = runBlocking {
        val key = System.getenv("LIVE_ANTHROPIC_KEY")
        assumeTrue(key != null)
        val service = AnthropicService(AppJson)
        for (model in listOf(AnthropicService.MODEL_SONNET, AnthropicService.MODEL_HAIKU)) {
            for (thinking in listOf(false, true)) {
                val result = service.parseRecipe(html, key!!, model, thinking)
                val parsed = result.getOrNull()?.result
                assertNotNull("$model (thinking=$thinking): ${result.exceptionOrNull()?.message}", parsed)
                assertEquals("Simple Banana Bread", parsed!!.name)
                assertTrue(parsed.instructionSections.flatMap { it.steps }.flatMap { it.ingredients }.size >= 8)
            }
        }
    }
}
