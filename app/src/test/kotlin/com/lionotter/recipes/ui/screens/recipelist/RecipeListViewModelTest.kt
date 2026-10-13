package com.lionotter.recipes.ui.screens.recipelist

import androidx.lifecycle.SavedStateHandle
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.usecase.DeleteRecipeUseCase
import com.lionotter.recipes.domain.usecase.GetTagsUseCase
import com.lionotter.recipes.imports.ImportMonitor
import com.lionotter.recipes.testutil.FakeImageStore
import com.lionotter.recipes.testutil.FakeMealPlanRepository
import com.lionotter.recipes.testutil.FakeRecipeRepository
import com.lionotter.recipes.testutil.FakeTagPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class RecipeListViewModelTest {

    private val recipeRepository = FakeRecipeRepository(
        recipe("cake", "Chocolate Cake", "dessert"),
        recipe("pie", "Apple Pie", "dessert"),
        recipe("soup", "Tomato Soup", "dinner")
    )
    private val mealPlanRepository = FakeMealPlanRepository()
    private val tagPreferences = FakeTagPreferences()

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun recipe(id: String, name: String, vararg tags: String) = Recipe(
        id = id,
        name = name,
        tags = tags.toList(),
        createdAt = Instant.fromEpochMilliseconds(1000),
        updatedAt = Instant.fromEpochMilliseconds(1000)
    )

    private fun createViewModel(handle: SavedStateHandle = SavedStateHandle()): RecipeListViewModel {
        val importMonitor = mockk<ImportMonitor> {
            every { inProgressRecipes } returns MutableStateFlow(emptyMap())
        }
        return RecipeListViewModel(
            handle,
            GetTagsUseCase(),
            tagPreferences,
            mockk(),
            importMonitor,
            recipeRepository,
            mealPlanRepository,
            DeleteRecipeUseCase(recipeRepository, mealPlanRepository, FakeImageStore()),
            UnconfinedTestDispatcher()
        )
    }

    @Test
    fun `showOnlyTag clears the search and filters by the tag, surviving process death`() {
        val handle = SavedStateHandle()
        val viewModel = createViewModel(handle)
        viewModel.onSearchQueryChange("cake")
        viewModel.onTagSelected("dinner")

        viewModel.showOnlyTag("dessert")

        assertEquals("", viewModel.searchQuery.value)
        assertEquals("dessert", viewModel.selectedTag.value)
        val restored = createViewModel(SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        assertEquals("dessert", restored.selectedTag.value)
    }

    @Test
    fun `recipes, load state and tags come from one subscription`() {
        val viewModel = createViewModel()

        val state = viewModel.uiState.value
        assertTrue(state.hasLoaded)
        assertEquals(listOf("Apple Pie", "Chocolate Cake", "Tomato Soup"), state.items.map { it.name })
        assertEquals(setOf("dessert", "dinner"), state.availableTags.toSet())
        assertEquals(1, recipeRepository.allRecipesSubscribers)
    }

    @Test
    fun `selection only includes recipes that are shown`() {
        val viewModel = createViewModel()
        viewModel.startSelection("cake")
        viewModel.setRecipeSelected("soup", selected = true)
        assertEquals(setOf("cake", "soup"), viewModel.uiState.value.selectedRecipeIds)

        viewModel.onSearchQueryChange("cake")

        assertEquals(setOf("cake"), viewModel.uiState.value.selectedRecipeIds)
    }

    @Test
    fun `a selected recipe deleted elsewhere leaves the selection`() {
        val viewModel = createViewModel()
        viewModel.startSelection("cake")
        viewModel.setRecipeSelected("pie", selected = true)

        recipeRepository.recipes.value -= "pie"

        assertEquals(setOf("cake"), viewModel.uiState.value.selectedRecipeIds)
    }

    @Test
    fun `bulk delete only deletes selected recipes that are shown`() {
        val viewModel = createViewModel()
        viewModel.startSelection("cake")
        viewModel.setRecipeSelected("soup", selected = true)
        viewModel.onSearchQueryChange("chocolate")

        viewModel.deleteSelectedRecipes()

        assertEquals(listOf("cake"), recipeRepository.deleted)
        assertEquals(listOf("cake"), mealPlanRepository.deletedForRecipes)
        assertFalse(viewModel.uiState.value.isMultiSelectActive)
    }

    @Test
    fun `deselecting the last recipe ends multi-select`() {
        val viewModel = createViewModel()
        viewModel.startSelection("cake")
        assertTrue(viewModel.uiState.value.isMultiSelectActive)

        viewModel.setRecipeSelected("cake", selected = false)

        assertFalse(viewModel.uiState.value.isMultiSelectActive)
    }

    @Test
    fun `setFavorite writes the value the user asked for`() {
        val viewModel = createViewModel()

        viewModel.setFavorite("cake", isFavorite = true)
        viewModel.setFavorite("cake", isFavorite = true)

        assertEquals(listOf("cake" to true, "cake" to true), recipeRepository.favoriteUpdates)
        assertTrue(recipeRepository.recipes.value.getValue("cake").isFavorite)
    }

    @Test
    fun `tag bar puts favorites with recipes first and follows the tag count`() {
        recipeRepository.recipes.value = (1..12).associate { i ->
            "r$i" to recipe("r$i", "Recipe $i", "own-$i", "group-${i % 3}")
        }
        val viewModel = createViewModel()
        assertEquals(10, viewModel.uiState.value.availableTags.size)

        tagPreferences.favoriteTagsFlow.value = setOf("OWN-7", "own-3", "gone")
        tagPreferences.tagBarSizeFlow.value = 5

        val tags = viewModel.uiState.value.availableTags
        assertEquals(5, tags.size)
        assertEquals(setOf("own-7", "own-3"), tags.take(2).toSet())
        assertFalse("gone" in tags)

        tagPreferences.favoriteTagsFlow.value = emptySet()
        tagPreferences.tagBarSizeFlow.value = 4

        assertEquals(4, viewModel.uiState.value.availableTags.size)
    }
}
