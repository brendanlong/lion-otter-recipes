package com.lionotter.recipes.ui.state

import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.cash.turbine.test
import com.lionotter.recipes.data.local.PendingImportEntity
import com.lionotter.recipes.data.repository.PendingImportRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InProgressRecipeManagerTest {

    private lateinit var manager: InProgressRecipeManager
    private lateinit var workManager: WorkManager
    private lateinit var pendingImportRepository: PendingImportRepository
    private lateinit var testScope: TestScope

    // In-memory backing store to simulate the database
    private val pendingImportsFlow = MutableStateFlow<List<PendingImportEntity>>(emptyList())
    private val pendingImportsStore = mutableMapOf<String, PendingImportEntity>()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>(), any()) } returns 0

        workManager = mockk {
            every { getWorkInfosByTagFlow(any()) } returns MutableStateFlow(emptyList<WorkInfo>())
        }

        pendingImportRepository = mockk {
            every { getAllPendingImports() } returns pendingImportsFlow

            coEvery { insertPendingImport(any()) } coAnswers {
                val entity = firstArg<PendingImportEntity>()
                pendingImportsStore[entity.id] = entity
                pendingImportsFlow.value = pendingImportsStore.values.toList()
            }

            coEvery { getPendingImportById(any()) } coAnswers {
                pendingImportsStore[firstArg<String>()]
            }

            coEvery { updateMetadata(any(), any(), any(), any()) } coAnswers {
                val id = arg<String>(0)
                val name = arg<String?>(1)
                val imageUrl = arg<String?>(2)
                val status = arg<String>(3)
                pendingImportsStore[id]?.let {
                    pendingImportsStore[id] = it.copy(name = name, imageUrl = imageUrl, status = status)
                    pendingImportsFlow.value = pendingImportsStore.values.toList()
                }
            }

            coEvery { updateStatus(any(), any()) } coAnswers {
                val id = arg<String>(0)
                val status = arg<String>(1)
                pendingImportsStore[id]?.let {
                    pendingImportsStore[id] = it.copy(status = status)
                    pendingImportsFlow.value = pendingImportsStore.values.toList()
                }
            }

            coEvery { deletePendingImport(any()) } coAnswers {
                val id = firstArg<String>()
                pendingImportsStore.remove(id)
                pendingImportsFlow.value = pendingImportsStore.values.toList()
            }
        }

        testScope = TestScope()
        manager = InProgressRecipeManager(workManager, pendingImportRepository, testScope)
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        pendingImportsStore.clear()
        pendingImportsFlow.value = emptyList()
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

            manager.addInProgressRecipe("recipe-1", "Test Recipe", url = "https://example.com")
            testScope.advanceUntilIdle()

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

            manager.addInProgressRecipe("recipe-1", "Recipe One", url = "https://example.com/1")
            testScope.advanceUntilIdle()
            awaitItem()

            manager.addInProgressRecipe("recipe-2", "Recipe Two", url = "https://example.com/2")
            testScope.advanceUntilIdle()
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

            manager.addInProgressRecipe("recipe-1", "Original Name", url = "https://example.com")
            testScope.advanceUntilIdle()
            awaitItem()

            manager.updateRecipeName("recipe-1", "Updated Name")
            testScope.advanceUntilIdle()
            val state = awaitItem()

            assertEquals("Updated Name", state["recipe-1"]?.name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateRecipeName does nothing for non-existent recipe`() = runTest {
        manager.inProgressRecipes.test {
            awaitItem() // Skip initial state

            manager.addInProgressRecipe("recipe-1", "Original Name", url = "https://example.com")
            testScope.advanceUntilIdle()
            val initialState = awaitItem()

            // Try to update non-existent recipe - should not emit new state
            manager.updateRecipeName("non-existent", "New Name")
            testScope.advanceUntilIdle()

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

            manager.addInProgressRecipe("recipe-1", "Recipe One", url = "https://example.com/1")
            testScope.advanceUntilIdle()
            awaitItem()

            manager.addInProgressRecipe("recipe-2", "Recipe Two", url = "https://example.com/2")
            testScope.advanceUntilIdle()
            awaitItem()

            manager.removeInProgressRecipe("recipe-1")
            testScope.advanceUntilIdle()
            val state = awaitItem()

            assertEquals(1, state.size)
            assertTrue(state.containsKey("recipe-2"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `removeInProgressRecipe handles non-existent recipe gracefully`() = runTest {
        manager.addInProgressRecipe("recipe-1", "Recipe One", url = "https://example.com")
        testScope.advanceUntilIdle()

        // Removing non-existent key should not throw
        manager.removeInProgressRecipe("non-existent")
        testScope.advanceUntilIdle()

        // Verify original recipe still exists
        manager.inProgressRecipes.test {
            val state = awaitItem()
            assertEquals(1, state.size)
            assertTrue(state.containsKey("recipe-1"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `cancelImport removes from state and calls repository`() = runTest {
        every { workManager.cancelWorkById(any()) } returns mockk()

        manager.addInProgressRecipe("recipe-1", "Recipe One", url = "https://example.com")
        testScope.advanceUntilIdle()

        manager.cancelImport("recipe-1")
        testScope.advanceUntilIdle()

        coVerify { pendingImportRepository.deletePendingImport("recipe-1") }

        manager.inProgressRecipes.test {
            val state = awaitItem()
            assertTrue(state.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `addInProgressRecipe with placeholder name stores null name`() = runTest {
        manager.addInProgressRecipe("recipe-1", "Importing recipe...", url = "https://example.com")
        testScope.advanceUntilIdle()

        coVerify {
            pendingImportRepository.insertPendingImport(match {
                it.id == "recipe-1" && it.name == null && it.url == "https://example.com"
            })
        }
    }

    @Test
    fun `InProgressRecipe data class holds correct values`() {
        val recipe = InProgressRecipe(id = "test-id", name = "Test Name")
        assertEquals("test-id", recipe.id)
        assertEquals("Test Name", recipe.name)
    }

    @Test
    fun `InProgressRecipe has default values for optional fields`() {
        val recipe = InProgressRecipe(id = "test-id", name = "Test Name")
        assertEquals(null, recipe.imageUrl)
        assertEquals(PendingImportEntity.STATUS_PENDING, recipe.status)
        assertEquals("", recipe.url)
        assertEquals(null, recipe.errorMessage)
    }
}
