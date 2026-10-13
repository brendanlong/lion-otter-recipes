package com.lionotter.recipes.live

import com.lionotter.recipes.data.ai.AnthropicRecipeParser
import com.lionotter.recipes.data.ai.AnthropicTagOrganizer
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.model.AiProviders
import com.lionotter.recipes.domain.model.TagChange
import com.lionotter.recipes.domain.model.TagUsage
import com.lionotter.recipes.domain.model.planTagChanges
import com.lionotter.recipes.domain.port.ParseAttachment
import com.lionotter.recipes.domain.port.ParseRequest
import com.lionotter.recipes.domain.port.ParsedRecipeWithUsage
import com.lionotter.recipes.domain.port.TagCleanupRequest
import com.lionotter.recipes.util.AppJson
import kotlinx.coroutines.Dispatchers
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
        <h2>Ingredients</h2><ul><li>3 bananas, mashed</li><li>1/3 cup melted butter</li><li>3/4 cup sugar</li>
        <li>1 egg</li><li>1 tsp vanilla</li><li>1 tsp baking soda</li><li>pinch of salt</li><li>1 1/2 cups flour</li></ul>
        <h2>Steps</h2><ol><li>Heat oven to 350F. Mash bananas with butter.</li><li>Mix in sugar, egg, vanilla.</li>
        <li>Stir in baking soda, salt, then flour.</li><li>Bake in a loaf pan 60 minutes.</li></ol></body></html>
    """.trimIndent()

    @Test
    fun parse() = runBlocking {
        val key = System.getenv("LIVE_ANTHROPIC_KEY")
        assumeTrue(key != null)
        val parser = AnthropicRecipeParser(AppJson, Dispatchers.IO)
        for (model in listOf(AiProviders.CLAUDE_SONNET, AiProviders.CLAUDE_HAIKU)) {
            for (thinking in listOf(false, true)) {
                val result = parser.parse(ParseRequest(html, key!!, model, thinking))
                val parsed = (result as? Outcome.Success)?.value?.recipe
                assertNotNull("$model (thinking=$thinking): $result", parsed)
                assertEquals("Simple Banana Bread", parsed!!.name)
                val ingredients = parsed.instructionSections.flatMap { it.steps }.flatMap { it.ingredients }
                assertTrue(ingredients.size >= 8)
                val bananas = ingredients.single { it.name.contains("banana", ignoreCase = true) }
                assertEquals("$model (thinking=$thinking): $bananas", "mashed", bananas.modifier?.lowercase())
                val butter = ingredients.single { it.name.contains("butter", ignoreCase = true) }
                assertEquals("$model (thinking=$thinking): $butter", "melted", butter.modifier?.lowercase())
                assertTrue(ingredients.none { it.name.contains("mashed") || it.name.contains("melted") })
            }
        }
    }

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/live/$name")) { "Missing fixture $name" }.use { it.readBytes() }

    private fun ingredientNames(result: Outcome<*>): List<String> {
        val parsed = ((result as? Outcome.Success)?.value as? ParsedRecipeWithUsage)?.recipe
        assertNotNull("$result", parsed)
        return parsed!!.instructionSections.flatMap { it.steps }.flatMap { it.ingredients }.map { it.name.lowercase() }
    }

    @Test
    fun parsePhoto() = runBlocking {
        val key = System.getenv("LIVE_ANTHROPIC_KEY")
        assumeTrue(key != null)
        val parser = AnthropicRecipeParser(AppJson, Dispatchers.IO)
        val photo = ParseAttachment.Image(fixture("recipe_card.png"), "image/png")

        val result = parser.parse(ParseRequest("", key!!, AiProviders.CLAUDE_SONNET, false, attachments = listOf(photo)))

        val ingredients = ingredientNames(result)
        assertEquals("Buttermilk Pancakes", (result as Outcome.Success).value.recipe.name)
        assertTrue("$ingredients", ingredients.size >= 7)
        assertTrue("$ingredients", ingredients.any { it.contains("buttermilk") })
    }

    @Test
    fun parsePdf() = runBlocking {
        val key = System.getenv("LIVE_ANTHROPIC_KEY")
        assumeTrue(key != null)
        val parser = AnthropicRecipeParser(AppJson, Dispatchers.IO)
        val pdf = ParseAttachment.Pdf(fixture("recipe.pdf"))

        val result = parser.parse(ParseRequest("", key!!, AiProviders.CLAUDE_HAIKU, false, attachments = listOf(pdf)))

        val ingredients = ingredientNames(result)
        val name = (result as Outcome.Success).value.recipe.name
        assertTrue(name, name.contains("Tomato Soup"))
        assertTrue("$ingredients", ingredients.size >= 8)
        assertTrue("$ingredients", ingredients.any { it.contains("tomato") })
    }

    @Test
    fun parseReusesExistingTags() = runBlocking {
        val key = System.getenv("LIVE_ANTHROPIC_KEY")
        assumeTrue(key != null)
        val parser = AnthropicRecipeParser(AppJson, Dispatchers.IO)
        val existingTags = listOf("Baking", "Breakfast", "Quick Bread", "Vegetarian", "Dessert")
        val result = parser.parse(ParseRequest(html, key!!, AiProviders.CLAUDE_HAIKU, false, existingTags = existingTags))
        val tags = (result as? Outcome.Success)?.value?.recipe?.tags
        assertNotNull("$result", tags)
        println("Tags with existing vocabulary: $tags")
        assertTrue("Expected an existing tag to be reused: $tags", tags!!.any { it in existingTags })
    }

    @Test
    fun tagCleanup() = runBlocking {
        val key = System.getenv("LIVE_ANTHROPIC_KEY")
        assumeTrue(key != null)
        val tags = listOf(
            TagUsage("Dessert", 12), TagUsage("desserts", 3), TagUsage("Sweets", 1), TagUsage("Baking", 9),
            TagUsage("baked goods", 2), TagUsage("Recipe", 7), TagUsage("Italian", 4), TagUsage("italian food", 1),
            TagUsage("Vegetarian", 6), TagUsage("veggie", 2), TagUsage("Quick", 5), TagUsage("Chicken", 4)
        )
        val organizer = AnthropicTagOrganizer(AppJson, Dispatchers.IO)
        for (thinking in listOf(false, true)) {
            val result = organizer.suggestTagChanges(TagCleanupRequest(tags, key!!, AiProviders.CLAUDE_HAIKU, thinking))
            val mappings = (result as? Outcome.Success)?.value
            assertNotNull("thinking=$thinking: $result", mappings)
            val changes = planTagChanges(tags, mappings!!)
            println("Tag cleanup (thinking=$thinking): $changes")
            val byTag = changes.associateBy { it.from }
            assertEquals("Dessert", byTag["desserts"]?.to)
            assertEquals(TagChange.Kind.DELETE, byTag["Recipe"]?.kind)
            assertTrue("Chicken should stay", "Chicken" !in byTag)
        }
    }
}
