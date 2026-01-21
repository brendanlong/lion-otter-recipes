package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.data.repository.RecipeRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GetTagsUseCaseTest {

    private lateinit var recipeRepository: RecipeRepository
    private lateinit var getTagsUseCase: GetTagsUseCase

    @Before
    fun setup() {
        recipeRepository = mockk()
        getTagsUseCase = GetTagsUseCase(recipeRepository)
    }

    @Test
    fun `returns empty list when no recipes exist`() = runTest {
        coEvery { recipeRepository.getAllRecipesWithTags() } returns emptyList()

        val result = getTagsUseCase.execute()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `returns all tags when fewer than 10 tags exist`() = runTest {
        coEvery { recipeRepository.getAllRecipesWithTags() } returns listOf(
            "recipe-1" to listOf("dinner", "easy"),
            "recipe-2" to listOf("dinner", "italian"),
            "recipe-3" to listOf("dessert", "quick")
        )

        val result = getTagsUseCase.execute()

        assertEquals(5, result.size)
        assertTrue(result.containsAll(listOf("dinner", "easy", "italian", "dessert", "quick")))
    }

    @Test
    fun `returns tags sorted by recipe count when fewer than 10`() = runTest {
        coEvery { recipeRepository.getAllRecipesWithTags() } returns listOf(
            "recipe-1" to listOf("dinner", "easy"),
            "recipe-2" to listOf("dinner", "italian"),
            "recipe-3" to listOf("dinner", "quick"),
            "recipe-4" to listOf("easy", "quick")
        )

        val result = getTagsUseCase.execute()

        // "dinner" appears in 3 recipes, should be first
        assertEquals("dinner", result[0])
    }

    @Test
    fun `returns exactly 10 tags when more than 10 exist`() = runTest {
        // Create 15 different tags spread across recipes
        val recipesWithTags = (1..15).map { i ->
            "recipe-$i" to listOf("tag-$i", "common-${i % 3}")
        }

        coEvery { recipeRepository.getAllRecipesWithTags() } returns recipesWithTags

        val result = getTagsUseCase.execute()

        assertEquals(10, result.size)
    }

    @Test
    fun `greedy algorithm selects tag covering most recipes first`() = runTest {
        // Tag "popular" covers 5 recipes, "niche" covers 1
        coEvery { recipeRepository.getAllRecipesWithTags() } returns listOf(
            "recipe-1" to listOf("popular", "tag-a"),
            "recipe-2" to listOf("popular", "tag-b"),
            "recipe-3" to listOf("popular", "tag-c"),
            "recipe-4" to listOf("popular", "tag-d"),
            "recipe-5" to listOf("popular", "tag-e"),
            "recipe-6" to listOf("niche", "tag-f"),
            "recipe-7" to listOf("tag-g"),
            "recipe-8" to listOf("tag-h"),
            "recipe-9" to listOf("tag-i"),
            "recipe-10" to listOf("tag-j"),
            "recipe-11" to listOf("tag-k")
        )

        val result = getTagsUseCase.execute()

        // "popular" should be selected and sorted by count (first in list)
        assertTrue(result.contains("popular"))
        assertEquals("popular", result[0])
    }

    @Test
    fun `handles recipes with no tags`() = runTest {
        coEvery { recipeRepository.getAllRecipesWithTags() } returns listOf(
            "recipe-1" to emptyList(),
            "recipe-2" to listOf("dinner"),
            "recipe-3" to emptyList()
        )

        val result = getTagsUseCase.execute()

        assertEquals(1, result.size)
        assertEquals("dinner", result[0])
    }

    @Test
    fun `handles single recipe with single tag`() = runTest {
        coEvery { recipeRepository.getAllRecipesWithTags() } returns listOf(
            "recipe-1" to listOf("dinner")
        )

        val result = getTagsUseCase.execute()

        assertEquals(1, result.size)
        assertEquals("dinner", result[0])
    }

    @Test
    fun `maximizes coverage with greedy set cover`() = runTest {
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

        val recipesWithTags = listOf(
            "recipe-1" to listOf("tag-a", "tag-l"),
            "recipe-2" to listOf("tag-a", "tag-b", "tag-l"),
            "recipe-3" to listOf("tag-b", "tag-c", "tag-l"),
            "recipe-4" to listOf("tag-c", "tag-d", "tag-l"),
            "recipe-5" to listOf("tag-d", "tag-e", "tag-l"),
            "recipe-6" to listOf("tag-e", "tag-f", "tag-l"),
            "recipe-7" to listOf("tag-f", "tag-g", "tag-l"),
            "recipe-8" to listOf("tag-g", "tag-h", "tag-l"),
            "recipe-9" to listOf("tag-h", "tag-i", "tag-l"),
            "recipe-10" to listOf("tag-i", "tag-j", "tag-l"),
            "recipe-11" to listOf("tag-j", "tag-k", "tag-l"),
            "recipe-12" to listOf("tag-k", "tag-l")
        )

        coEvery { recipeRepository.getAllRecipesWithTags() } returns recipesWithTags

        val result = getTagsUseCase.execute()

        assertEquals(10, result.size)
        // tag-l covers all recipes, so it should be in the result and sorted first
        assertEquals("tag-l", result[0])
    }

    @Test
    fun `returns tags sorted by total recipe count after selection`() = runTest {
        coEvery { recipeRepository.getAllRecipesWithTags() } returns listOf(
            "recipe-1" to listOf("common", "rare-a"),
            "recipe-2" to listOf("common", "rare-b"),
            "recipe-3" to listOf("common", "rare-c"),
            "recipe-4" to listOf("semi-common", "rare-d"),
            "recipe-5" to listOf("semi-common", "rare-e")
        )

        val result = getTagsUseCase.execute()

        // Results should be sorted by count: common (3), semi-common (2), then rares (1 each)
        assertEquals("common", result[0])
        assertEquals("semi-common", result[1])
    }

    @Test
    fun `handles duplicate tags across recipes correctly`() = runTest {
        coEvery { recipeRepository.getAllRecipesWithTags() } returns listOf(
            "recipe-1" to listOf("dinner", "dinner"), // Duplicate in same recipe
            "recipe-2" to listOf("dinner"),
            "recipe-3" to listOf("lunch")
        )

        val result = getTagsUseCase.execute()

        // Should count unique recipes per tag, not tag occurrences
        assertTrue(result.contains("dinner"))
        assertTrue(result.contains("lunch"))
    }

    @Test
    fun `selects diverse tags to cover all recipes`() = runTest {
        // Create a scenario where we need multiple tags to cover all recipes
        coEvery { recipeRepository.getAllRecipesWithTags() } returns listOf(
            "recipe-1" to listOf("breakfast"),
            "recipe-2" to listOf("lunch"),
            "recipe-3" to listOf("dinner"),
            "recipe-4" to listOf("dessert"),
            "recipe-5" to listOf("snack")
        )

        val result = getTagsUseCase.execute()

        // All 5 tags should be selected since each covers a unique recipe
        assertEquals(5, result.size)
        assertTrue(result.containsAll(listOf("breakfast", "lunch", "dinner", "dessert", "snack")))
    }
}
