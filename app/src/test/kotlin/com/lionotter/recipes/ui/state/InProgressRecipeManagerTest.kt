package com.lionotter.recipes.ui.state

import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InProgressRecipeManagerTest {

    private lateinit var manager: InProgressRecipeManager
    private lateinit var workManager: WorkManager
    private lateinit var testScope: TestScope

    @Before
    fun setup() {
        workManager = mockk {
            every { getWorkInfosByTagFlow(any()) } returns MutableStateFlow(emptyList<WorkInfo>())
        }
        testScope = TestScope()
        manager = InProgressRecipeManager(workManager, testScope)
    }

    @Test
    fun `initial state is empty map`() = runTest {
        manager.inProgressRecipes.test {
            assertEquals(emptyMap<String, InProgressRecipe>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `addInProgressRecipe adds recipe to state`() = runTest {
        manager.inProgressRecipes.test {
            // Skip initial empty state
            awaitItem()

            manager.addInProgressRecipe("recipe-1", "Test Recipe")

            val state = awaitItem()
            assertEquals(1, state.size)
            assertEquals("recipe-1", state["recipe-1"]?.id)
            assertEquals("Test Recipe", state["recipe-1"]?.name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `addInProgressRecipe adds multiple recipes`() = runTest {
        manager.inProgressRecipes.test {
            awaitItem() // Skip initial state

            manager.addInProgressRecipe("recipe-1", "Recipe One")
            awaitItem()

            manager.addInProgressRecipe("recipe-2", "Recipe Two")
            val state = awaitItem()

            assertEquals(2, state.size)
            assertEquals("Recipe One", state["recipe-1"]?.name)
            assertEquals("Recipe Two", state["recipe-2"]?.name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateRecipeName updates existing recipe`() = runTest {
        manager.inProgressRecipes.test {
            awaitItem() // Skip initial state

            manager.addInProgressRecipe("recipe-1", "Original Name")
            awaitItem()

            manager.updateRecipeName("recipe-1", "Updated Name")
            val state = awaitItem()

            assertEquals("Updated Name", state["recipe-1"]?.name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateRecipeName does nothing for non-existent recipe`() = runTest {
        manager.inProgressRecipes.test {
            awaitItem() // Skip initial state

            manager.addInProgressRecipe("recipe-1", "Original Name")
            val initialState = awaitItem()

            // Try to update non-existent recipe - should not emit new state
            manager.updateRecipeName("non-existent", "New Name")

            // Give it a moment to potentially emit
            expectNoEvents()

            // State should remain unchanged
            assertEquals("Original Name", initialState["recipe-1"]?.name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `removeInProgressRecipe removes recipe from state`() = runTest {
        manager.inProgressRecipes.test {
            awaitItem() // Skip initial state

            manager.addInProgressRecipe("recipe-1", "Recipe One")
            awaitItem()

            manager.addInProgressRecipe("recipe-2", "Recipe Two")
            awaitItem()

            manager.removeInProgressRecipe("recipe-1")
            val state = awaitItem()

            assertEquals(1, state.size)
            assertTrue(state.containsKey("recipe-2"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `removeInProgressRecipe handles non-existent recipe gracefully`() = runTest {
        manager.addInProgressRecipe("recipe-1", "Recipe One")

        // Removing non-existent key should not throw
        manager.removeInProgressRecipe("non-existent")

        // Verify original recipe still exists
        manager.inProgressRecipes.test {
            val state = awaitItem()
            assertEquals(1, state.size)
            assertTrue(state.containsKey("recipe-1"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `clear removes all recipes`() = runTest {
        manager.inProgressRecipes.test {
            awaitItem() // Skip initial state

            manager.addInProgressRecipe("recipe-1", "Recipe One")
            awaitItem()

            manager.addInProgressRecipe("recipe-2", "Recipe Two")
            awaitItem()

            manager.clear()
            val state = awaitItem()

            assertTrue(state.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `InProgressRecipe data class holds correct values`() {
        val recipe = InProgressRecipe(id = "test-id", name = "Test Name")
        assertEquals("test-id", recipe.id)
        assertEquals("Test Name", recipe.name)
    }
}
