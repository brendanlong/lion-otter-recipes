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
    fun `greedy algorithm selects tag covering most recipes first`() {
        // Tag "popular" covers 5 recipes, "niche" covers 1
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

        // "popular" should be selected and sorted by count (first in list)
        assertTrue(result.contains("popular"))
        assertEquals("popular", result[0])
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
    fun `maximizes coverage with greedy set cover`() {
        // This test verifies the greedy set cover algorithm
        // Recipe coverage:
        // tag-a covers recipe-1, recipe-2 (2 recipes)
        // tag-b covers recipe-2, recipe-3 (2 recipes)
        // tag-c covers recipe-3, recipe-4 (2 recipes)
        // tag-d covers recipe-4, recipe-5 (2 recipes)
        // tag-e covers recipe-5, recipe-6 (2 recipes)
        // tag-f covers recipe-6, recipe-7 (2 recipes)
        // tag-g covers recipe-7, recipe-8 (2 recipes)
        // tag-h covers recipe-8, recipe-9 (2 recipes)
        // tag-i covers recipe-9, recipe-10 (2 recipes)
        // tag-j covers recipe-10, recipe-11 (2 recipes)
        // tag-k covers recipe-11, recipe-12 (2 recipes)
        // tag-l covers all 12 recipes (would be selected first)

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
        // tag-l covers all recipes, so it should be in the result and sorted first
        assertEquals("tag-l", result[0])
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
}
