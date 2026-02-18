package com.lionotter.recipes.integration

import app.cash.turbine.turbineScope
import com.lionotter.recipes.domain.model.Amount
import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.InstructionStep
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.ui.state.InProgressRecipe
import com.lionotter.recipes.ui.state.RecipeListItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/**
 * Integration test that replicates RecipeListViewModel's exact combine + runningFold
 * pipeline, using real Firestore + real RecipeRepository.
 *
 * This tests whether the favorite toggle works through the same flow transformation
 * chain that the ViewModel uses, without needing to construct the full ViewModel
 * (which requires WorkManager, PendingImportRepository, etc.).
 */
class ViewModelFavoriteIntegrationTest : FirestoreIntegrationTest() {

    private val now = Instant.fromEpochMilliseconds(1700000000000)

    private fun createTestRecipe(
        id: String = "test-recipe-1",
        name: String = "Test Recipe",
        isFavorite: Boolean = false
    ) = Recipe(
        id = id,
        name = name,
        instructionSections = listOf(
            InstructionSection(
                name = null,
                steps = listOf(
                    InstructionStep(
                        stepNumber = 1,
                        instruction = "Test step.",
                        ingredients = listOf(
                            Ingredient(
                                name = "test ingredient",
                                amount = Amount(value = 1.0, unit = "cup")
                            )
                        )
                    )
                )
            )
        ),
        tags = listOf("test"),
        equipment = listOf("bowl"),
        createdAt = now,
        updatedAt = now,
        isFavorite = isFavorite
    )

    // -----------------------------------------------------------------------
    // Exact copy of ViewModel's SortState and flow pipeline
    // -----------------------------------------------------------------------

    private data class SortState(
        val sortedIds: List<String> = emptyList(),
        val lastQuery: String = "",
        val lastTag: String? = null
    )

    /**
     * Builds the exact same flow pipeline as RecipeListViewModel.recipes,
     * but using our real recipeRepository and stub in-progress recipes.
     */
    private fun buildRecipeListFlow(
        searchQuery: MutableStateFlow<String> = MutableStateFlow(""),
        selectedTag: MutableStateFlow<String?> = MutableStateFlow(null),
        inProgressRecipes: MutableStateFlow<Map<String, InProgressRecipe>> = MutableStateFlow(emptyMap())
    ) = combine(
        recipeRepository.getAllRecipes(),
        inProgressRecipes,
        searchQuery,
        selectedTag,
    ) { allRecipes, inProgress, query, tag ->
        val items = buildList {
            inProgress.values.forEach { add(RecipeListItem.InProgress(it)) }
            allRecipes.forEach { add(RecipeListItem.Saved(it)) }
        }

        val filteredItems = items.filter { item ->
            val matchesSearch = query.isBlank() ||
                item.name.contains(query, ignoreCase = true) ||
                (item is RecipeListItem.Saved && item.recipe.tags.any {
                    it.contains(query, ignoreCase = true)
                })

            val matchesTag = tag == null ||
                (item is RecipeListItem.Saved && item.recipe.tags.any {
                    it.equals(tag, ignoreCase = true)
                })

            matchesSearch && matchesTag
        }

        Triple(filteredItems, query, tag)
    }.runningFold(
        Pair(SortState(), emptyList<RecipeListItem>())
    ) { (prevState, _), (filteredItems, query, tag) ->
        val currentIds = filteredItems.map { it.id }.toSet()
        val filtersChanged = query != prevState.lastQuery || tag != prevState.lastTag
        val idsChanged = currentIds != prevState.sortedIds.toSet()

        if (filtersChanged || idsChanged) {
            val sorted = filteredItems.sortedWith(
                compareByDescending<RecipeListItem> {
                    when (it) {
                        is RecipeListItem.InProgress -> true
                        is RecipeListItem.Saved -> it.recipe.isFavorite
                    }
                }.thenBy { it.name.lowercase() }
            )
            val newState = SortState(
                sortedIds = sorted.map { it.id },
                lastQuery = query,
                lastTag = tag
            )
            Pair(newState, sorted)
        } else {
            val itemsById = filteredItems.associateBy { it.id }
            val ordered = prevState.sortedIds.mapNotNull { id -> itemsById[id] }
            Pair(prevState, ordered)
        }
    }.map { (_, items) -> items }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    fun `toggleFavorite through ViewModel pipeline updates the list`() = runTest {
        val recipe = createTestRecipe(isFavorite = false)
        recipeRepository.saveRecipe(recipe)
        pumpLooper()

        turbineScope {
            val flow = buildRecipeListFlow()
            val turbine = flow.testIn(backgroundScope)
            pumpLooper()

            // Initial emission from runningFold (empty list)
            val initial0 = turbine.awaitItem()
            assertEquals("runningFold seed is empty", 0, initial0.size)

            // First real emission: recipe list
            val initial = turbine.awaitItem()
            assertTrue("Should have one recipe", initial.isNotEmpty())
            val savedItem = initial[0] as RecipeListItem.Saved
            assertFalse("Initial: not favorite", savedItem.recipe.isFavorite)

            // Mimic ViewModel.toggleFavorite: read current state, flip, write
            val currentFavorite = savedItem.recipe.isFavorite
            val newFavorite = !currentFavorite
            recipeRepository.setFavorite(recipe.id, newFavorite)
            pumpLooper()

            // The pipeline should emit an updated list with isFavorite=true
            val updated = turbine.awaitItem()
            assertTrue("Should still have one recipe", updated.isNotEmpty())
            val updatedItem = updated[0] as RecipeListItem.Saved
            assertTrue(
                "After toggle: expected isFavorite=true but got ${updatedItem.recipe.isFavorite}",
                updatedItem.recipe.isFavorite
            )

            // Toggle back: read from the emitted list, flip, write
            val currentFavorite2 = updatedItem.recipe.isFavorite
            val newFavorite2 = !currentFavorite2
            recipeRepository.setFavorite(recipe.id, newFavorite2)
            pumpLooper()

            val reverted = turbine.awaitItem()
            assertTrue("Should still have one recipe", reverted.isNotEmpty())
            val revertedItem = reverted[0] as RecipeListItem.Saved
            assertFalse(
                "After second toggle: expected isFavorite=false but got ${revertedItem.recipe.isFavorite}",
                revertedItem.recipe.isFavorite
            )

            turbine.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `favorite toggle emits distinct values through runningFold`() = runTest {
        // Save two recipes, one favorited one not
        val recipe1 = createTestRecipe(id = "recipe-1", name = "Apple Pie", isFavorite = false)
        val recipe2 = createTestRecipe(id = "recipe-2", name = "Banana Bread", isFavorite = true)
        recipeRepository.saveRecipe(recipe1)
        recipeRepository.saveRecipe(recipe2)
        pumpLooper()

        turbineScope {
            val flow = buildRecipeListFlow()
            val turbine = flow.testIn(backgroundScope)
            pumpLooper()

            // Skip seed emission
            turbine.awaitItem()

            // We may get intermediate emissions as both recipes land — consume until stable
            var items: List<RecipeListItem>
            do {
                items = turbine.awaitItem()
                pumpLooper()
            } while (items.size < 2)

            // Banana Bread (favorite) should sort before Apple Pie (not favorite)
            assertEquals("recipe-2", items[0].id) // Banana Bread (favorite)
            assertEquals("recipe-1", items[1].id) // Apple Pie

            // Now toggle Apple Pie to favorite — IDs don't change, so runningFold
            // should take the "else" branch (maintain order, update data)
            recipeRepository.setFavorite("recipe-1", true)
            pumpLooper()

            val updated = turbine.awaitItem()
            assertEquals(2, updated.size)

            // Both should now be favorite
            val item1 = updated.find { it.id == "recipe-1" } as RecipeListItem.Saved
            val item2 = updated.find { it.id == "recipe-2" } as RecipeListItem.Saved
            assertTrue("recipe-1 should be favorite", item1.recipe.isFavorite)
            assertTrue("recipe-2 should still be favorite", item2.recipe.isFavorite)

            turbine.cancelAndIgnoreRemainingEvents()
        }
    }
}
