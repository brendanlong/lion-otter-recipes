package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.model.Recipe
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GetTagsUseCaseTest {

    private lateinit var getTagsUseCase: GetTagsUseCase

    private val now = Instant.fromEpochMilliseconds(0)

    private fun recipe(id: String, tags: List<String>) = Recipe(
        id = id,
        name = "Recipe $id",
        tags = tags,
        createdAt = now,
        updatedAt = now
    )

    @Before
    fun setup() {
        getTagsUseCase = GetTagsUseCase()
    }

    @Test
    fun `returns empty list when no recipes exist`() {
        val result = getTagsUseCase.execute(emptyList())

        assertTrue(result.isEmpty())
    }

    @Test
    fun `returns all tags when fewer than 10 tags exist`() {
        val recipes = listOf(
            recipe("recipe-1", listOf("dinner", "easy")),
            recipe("recipe-2", listOf("dinner", "italian")),
            recipe("recipe-3", listOf("dessert", "quick"))
        )

        val result = getTagsUseCase.execute(recipes)

        assertEquals(5, result.size)
        assertTrue(result.containsAll(listOf("dinner", "easy", "italian", "dessert", "quick")))
    }

    @Test
    fun `returns tags sorted by recipe count when fewer than 10`() {
        val recipes = listOf(
            recipe("recipe-1", listOf("dinner", "easy")),
            recipe("recipe-2", listOf("dinner", "italian")),
            recipe("recipe-3", listOf("dinner", "quick")),
            recipe("recipe-4", listOf("easy", "quick"))
        )

        val result = getTagsUseCase.execute(recipes)

        // "dinner" appears in 3 recipes, should be first
        assertEquals("dinner", result[0])
    }

    @Test
    fun `returns exactly 10 tags when more than 10 exist`() {
        // Create 15 different tags spread across recipes
        val recipes = (1..15).map { i ->
            recipe("recipe-$i", listOf("tag-$i", "common-${i % 3}"))
        }

        val result = getTagsUseCase.execute(recipes)

        assertEquals(10, result.size)
    }

    @Test
    fun `ILP selects tags that cover all recipes when possible`() {
        // 11 recipes each with a unique tag plus a "popular" tag on 5 of them
        val recipes = listOf(
            recipe("recipe-1", listOf("popular", "tag-a")),
            recipe("recipe-2", listOf("popular", "tag-b")),
            recipe("recipe-3", listOf("popular", "tag-c")),
            recipe("recipe-4", listOf("popular", "tag-d")),
            recipe("recipe-5", listOf("popular", "tag-e")),
            recipe("recipe-6", listOf("niche", "tag-f")),
            recipe("recipe-7", listOf("tag-g")),
            recipe("recipe-8", listOf("tag-h")),
            recipe("recipe-9", listOf("tag-i")),
            recipe("recipe-10", listOf("tag-j")),
            recipe("recipe-11", listOf("tag-k"))
        )

        val result = getTagsUseCase.execute(recipes)

        // Should select exactly 10 tags
        assertEquals(10, result.size)

        // All recipes should be covered by at least one selected tag
        for (recipe in recipes) {
            assertTrue(
                "Recipe ${recipe.id} should be covered by at least one selected tag",
                recipe.tags.any { it in result }
            )
        }
    }

    @Test
    fun `handles recipes with no tags`() {
        val recipes = listOf(
            recipe("recipe-1", emptyList()),
            recipe("recipe-2", listOf("dinner")),
            recipe("recipe-3", emptyList())
        )

        val result = getTagsUseCase.execute(recipes)

        assertEquals(1, result.size)
        assertEquals("dinner", result[0])
    }

    @Test
    fun `handles single recipe with single tag`() {
        val recipes = listOf(
            recipe("recipe-1", listOf("dinner"))
        )

        val result = getTagsUseCase.execute(recipes)

        assertEquals(1, result.size)
        assertEquals("dinner", result[0])
    }

    @Test
    fun `ILP covers all recipes in chain coverage scenario`() {
        // Chain of overlapping tags - need to pick the right combination
        val recipes = listOf(
            recipe("recipe-1", listOf("tag-a", "tag-l")),
            recipe("recipe-2", listOf("tag-a", "tag-b", "tag-l")),
            recipe("recipe-3", listOf("tag-b", "tag-c", "tag-l")),
            recipe("recipe-4", listOf("tag-c", "tag-d", "tag-l")),
            recipe("recipe-5", listOf("tag-d", "tag-e", "tag-l")),
            recipe("recipe-6", listOf("tag-e", "tag-f", "tag-l")),
            recipe("recipe-7", listOf("tag-f", "tag-g", "tag-l")),
            recipe("recipe-8", listOf("tag-g", "tag-h", "tag-l")),
            recipe("recipe-9", listOf("tag-h", "tag-i", "tag-l")),
            recipe("recipe-10", listOf("tag-i", "tag-j", "tag-l")),
            recipe("recipe-11", listOf("tag-j", "tag-k", "tag-l")),
            recipe("recipe-12", listOf("tag-k", "tag-l"))
        )

        val result = getTagsUseCase.execute(recipes)

        assertEquals(10, result.size)
        // All recipes should be covered
        for (recipe in recipes) {
            assertTrue(
                "Recipe ${recipe.id} should be covered",
                recipe.tags.any { it in result }
            )
        }
    }

    @Test
    fun `returns tags sorted by total recipe count after selection`() {
        val recipes = listOf(
            recipe("recipe-1", listOf("common", "rare-a")),
            recipe("recipe-2", listOf("common", "rare-b")),
            recipe("recipe-3", listOf("common", "rare-c")),
            recipe("recipe-4", listOf("semi-common", "rare-d")),
            recipe("recipe-5", listOf("semi-common", "rare-e"))
        )

        val result = getTagsUseCase.execute(recipes)

        // Results should be sorted by count: common (3), semi-common (2), then rares (1 each)
        assertEquals("common", result[0])
        assertEquals("semi-common", result[1])
    }

    @Test
    fun `handles duplicate tags across recipes correctly`() {
        val recipes = listOf(
            recipe("recipe-1", listOf("dinner", "dinner")), // Duplicate in same recipe
            recipe("recipe-2", listOf("dinner")),
            recipe("recipe-3", listOf("lunch"))
        )

        val result = getTagsUseCase.execute(recipes)

        // Should count unique recipes per tag, not tag occurrences
        assertTrue(result.contains("dinner"))
        assertTrue(result.contains("lunch"))
    }

    @Test
    fun `selects diverse tags to cover all recipes`() {
        // Create a scenario where we need multiple tags to cover all recipes
        val recipes = listOf(
            recipe("recipe-1", listOf("breakfast")),
            recipe("recipe-2", listOf("lunch")),
            recipe("recipe-3", listOf("dinner")),
            recipe("recipe-4", listOf("dessert")),
            recipe("recipe-5", listOf("snack"))
        )

        val result = getTagsUseCase.execute(recipes)

        // All 5 tags should be selected since each covers a unique recipe
        assertEquals(5, result.size)
        assertTrue(result.containsAll(listOf("breakfast", "lunch", "dinner", "dessert", "snack")))
    }

    @Test
    fun `ILP deprioritizes near-universal tags`() {
        // "easy" and "quick" cover all 20 recipes — they provide almost no filtering value.
        // With 10 specific tags that partition the 20 recipes (2 each), the ILP should
        // prefer those 10 specific tags over wasting slots on "easy" or "quick".
        val recipes = mutableListOf<Recipe>()
        // 20 recipes across 10 specific tags (2 recipes each), plus 2 generic tags
        for (i in 1..20) {
            recipes.add(
                recipe(
                    "recipe-$i",
                    listOf("easy", "quick", "specific-${(i - 1) / 2 + 1}")
                )
            )
        }

        val result = getTagsUseCase.execute(recipes)

        assertEquals(10, result.size)
        // 10 specific tags can cover everything; "easy" and "quick" should be excluded
        assertTrue(
            "Near-universal tags should not be selected: $result",
            "easy" !in result && "quick" !in result
        )
        // All selected tags should be the specific ones
        assertTrue(
            "All selected tags should be specific: $result",
            result.all { it.startsWith("specific-") }
        )
    }

    @Test
    fun `ILP handles degenerate case with more unique recipes than k`() {
        // 12 recipes, each with a unique tag and no overlap
        // Can only pick 10 tags, so 2 recipes will be uncovered
        val recipes = (1..12).map { i ->
            recipe("recipe-$i", listOf("unique-tag-$i"))
        }

        val result = getTagsUseCase.execute(recipes)

        assertEquals(10, result.size)
        // Should still return 10 valid tags
        assertTrue(result.all { tag -> tag.startsWith("unique-tag-") })
    }

    @Test
    fun `greedy fallback works correctly`() {
        val recipes = listOf(
            recipe("recipe-1", listOf("a", "b")),
            recipe("recipe-2", listOf("b", "c")),
            recipe("recipe-3", listOf("c", "d")),
            recipe("recipe-4", listOf("d", "e")),
            recipe("recipe-5", listOf("e", "f")),
            recipe("recipe-6", listOf("f", "g")),
            recipe("recipe-7", listOf("g", "h")),
            recipe("recipe-8", listOf("h", "i")),
            recipe("recipe-9", listOf("i", "j")),
            recipe("recipe-10", listOf("j", "k")),
            recipe("recipe-11", listOf("k", "l"))
        )

        val tagToRecipes = mutableMapOf<String, Set<String>>()
        for (recipe in recipes) {
            for (tag in recipe.tags) {
                tagToRecipes[tag] = (tagToRecipes[tag] ?: emptySet()) + recipe.id
            }
        }

        val result = getTagsUseCase.solveGreedy(recipes, tagToRecipes)

        assertEquals(10, result.size)
        // All recipes should be covered
        for (recipe in recipes) {
            assertTrue(
                "Recipe ${recipe.id} should be covered by greedy selection",
                recipe.tags.any { it in result }
            )
        }
    }

    @Test
    fun `ILP maximizes multi-tag coverage`() {
        // Recipes appear under multiple tags; ILP should maximize total appearances
        val recipes = listOf(
            recipe("recipe-1", listOf("tag-a", "tag-b", "tag-c")),
            recipe("recipe-2", listOf("tag-a", "tag-d", "tag-e")),
            recipe("recipe-3", listOf("tag-b", "tag-d", "tag-f")),
            recipe("recipe-4", listOf("tag-c", "tag-e", "tag-f")),
            recipe("recipe-5", listOf("tag-g", "tag-h")),
            recipe("recipe-6", listOf("tag-h", "tag-i")),
            recipe("recipe-7", listOf("tag-i", "tag-j")),
            recipe("recipe-8", listOf("tag-j", "tag-k")),
        )

        val result = getTagsUseCase.execute(recipes)

        // All recipes should be covered
        for (recipe in recipes) {
            assertTrue(
                "Recipe ${recipe.id} should be covered",
                recipe.tags.any { it in result }
            )
        }
    }
}
