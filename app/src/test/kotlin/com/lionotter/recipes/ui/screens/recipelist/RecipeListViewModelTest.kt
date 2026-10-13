package com.lionotter.recipes.ui.screens.recipelist

import androidx.lifecycle.SavedStateHandle
import com.lionotter.recipes.data.repository.IRecipeRepository
import com.lionotter.recipes.domain.usecase.GetTagsUseCase
import com.lionotter.recipes.imports.ImportMonitor
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecipeListViewModelTest {

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(handle: SavedStateHandle): RecipeListViewModel {
        val recipeRepository = mockk<IRecipeRepository> {
            every { getAllRecipes() } returns flowOf(emptyList())
        }
        val importMonitor = mockk<ImportMonitor> {
            every { inProgressRecipes } returns MutableStateFlow(emptyMap())
        }
        return RecipeListViewModel(handle, GetTagsUseCase(), mockk(), importMonitor, recipeRepository, mockk())
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
}
