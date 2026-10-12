package com.lionotter.recipes.ui.state

import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.cash.turbine.test
import com.lionotter.recipes.data.local.PendingImportEntity
import com.lionotter.recipes.data.repository.PendingImportRepository
import com.lionotter.recipes.worker.BaseRecipeWorker
import com.lionotter.recipes.worker.RecipeImportWorker
import androidx.work.workDataOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import com.lionotter.recipes.worker.RecipeWorkScheduler
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import java.util.UUID
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    private val importWorkInfos = MutableStateFlow<List<WorkInfo>>(emptyList())
    private val workInfosById = mutableMapOf<UUID, WorkInfo>()
    private lateinit var scheduler: RecipeWorkScheduler

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        workManager = mockk {
            every { getWorkInfosByTagFlow(any()) } returns MutableStateFlow(emptyList<WorkInfo>())
            every { getWorkInfosByTagFlow(RecipeImportWorker.TAG_RECIPE_IMPORT) } returns importWorkInfos
            every { getWorkInfoByIdFlow(any()) } answers { flowOf(workInfosById[firstArg()]) }
            every { cancelUniqueWork(any()) } returns mockk()
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

            coEvery { getPendingImportByWorkManagerId(any()) } coAnswers {
                pendingImportsStore.values.firstOrNull { it.workManagerId == firstArg<String>() }
            }

            coEvery { updateStatusWithError(any(), any(), any()) } coAnswers {
                val id = arg<String>(0)
                pendingImportsStore[id]?.let {
                    pendingImportsStore[id] = it.copy(status = arg(1), errorMessage = arg(2))
                    pendingImportsFlow.value = pendingImportsStore.values.toList()
                }
            }

            coEvery { deletePendingImport(any()) } coAnswers {
                val id = firstArg<String>()
                pendingImportsStore.remove(id)
                pendingImportsFlow.value = pendingImportsStore.values.toList()
            }
        }

        scheduler = mockk(relaxed = true) {
            every { buildUrlImportRequest(any(), any()) } answers {
                OneTimeWorkRequestBuilder<RecipeImportWorker>().build()
            }
            every { enqueueImport(any(), any()) } answers {
                val request = secondArg<OneTimeWorkRequest>()
                workInfosById[request.id] = WorkInfo(request.id, WorkInfo.State.ENQUEUED, emptySet())
            }
        }

        testScope = TestScope()
        manager = createManager()
    }

    private fun createManager() = InProgressRecipeManager(
        workManager,
        pendingImportRepository,
        testScope,
        scheduler = scheduler,
        inputStore = mockk(relaxed = true),
        importPaprikaUseCase = mockk(relaxed = true)
    )

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        pendingImportsStore.clear()
        pendingImportsFlow.value = emptyList()
        workInfosById.clear()
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
    fun `cancelImport removes from state and cancels WorkManager work`() = runTest {
        every { workManager.cancelWorkById(any()) } returns mockk()

        val workUuid = java.util.UUID.randomUUID()
        manager.addInProgressRecipe(
            "recipe-1", "Recipe One",
            url = "https://example.com",
            workManagerId = workUuid.toString()
        )
        testScope.advanceUntilIdle()

        manager.cancelImport("recipe-1")
        testScope.advanceUntilIdle()

        coVerify { pendingImportRepository.deletePendingImport("recipe-1") }
        io.mockk.verify { workManager.cancelWorkById(workUuid) }

        manager.inProgressRecipes.test {
            val state = awaitItem()
            assertTrue(state.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `cancelImport without workManagerId still removes from state`() = runTest {
        manager.addInProgressRecipe("recipe-1", "Recipe One", url = "https://example.com")
        testScope.advanceUntilIdle()

        manager.cancelImport("recipe-1")
        testScope.advanceUntilIdle()

        coVerify { pendingImportRepository.deletePendingImport("recipe-1") }
        io.mockk.verify(exactly = 0) { workManager.cancelWorkById(any()) }

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

    @Test
    fun `failed work marks the matching import failed with its error message`() = runTest {
        val workId = java.util.UUID.randomUUID()
        manager.addInProgressRecipe("import-1", "Recipe", url = "https://example.com", workManagerId = workId.toString())
        testScope.advanceUntilIdle()

        importWorkInfos.value = listOf(
            WorkInfo(
                id = workId,
                state = WorkInfo.State.FAILED,
                tags = setOf(RecipeImportWorker.TAG_RECIPE_IMPORT),
                outputData = workDataOf(BaseRecipeWorker.KEY_ERROR_MESSAGE to "The site blocked the request (403).")
            )
        )
        testScope.advanceUntilIdle()

        val entity = pendingImportsStore["import-1"]!!
        assertEquals(PendingImportEntity.STATUS_FAILED, entity.status)
        assertEquals("The site blocked the request (403).", entity.errorMessage)
    }

    @Test
    fun `failed work without output still marks the import failed`() = runTest {
        val workId = java.util.UUID.randomUUID()
        manager.addInProgressRecipe("import-1", "Recipe", url = "https://example.com", workManagerId = workId.toString())
        testScope.advanceUntilIdle()

        importWorkInfos.value = listOf(
            WorkInfo(id = workId, state = WorkInfo.State.FAILED, tags = setOf(RecipeImportWorker.TAG_RECIPE_IMPORT))
        )
        testScope.advanceUntilIdle()

        assertEquals(PendingImportEntity.STATUS_FAILED, pendingImportsStore["import-1"]!!.status)
    }

    @Test
    fun `succeeded work removes the import and unrelated work is ignored`() = runTest {
        val workId = java.util.UUID.randomUUID()
        manager.addInProgressRecipe("import-1", "Recipe", url = "https://example.com", workManagerId = workId.toString())
        manager.addInProgressRecipe("import-2", "Other", url = "https://example.org", workManagerId = java.util.UUID.randomUUID().toString())
        testScope.advanceUntilIdle()

        importWorkInfos.value = listOf(
            WorkInfo(id = workId, state = WorkInfo.State.SUCCEEDED, tags = setOf(RecipeImportWorker.TAG_RECIPE_IMPORT)),
            WorkInfo(id = java.util.UUID.randomUUID(), state = WorkInfo.State.SUCCEEDED, tags = setOf(RecipeImportWorker.TAG_RECIPE_IMPORT))
        )
        testScope.advanceUntilIdle()

        assertEquals(setOf("import-2"), pendingImportsStore.keys)
    }

    @Test
    fun `startUrlImport returns the existing import while its work is active`() = runTest {
        val first = manager.startUrlImport("https://example.com/r")
        val second = manager.startUrlImport("https://example.com/r")

        assertEquals(first, second)
        assertEquals(1, pendingImportsStore.size)
    }

    @Test
    fun `startUrlImport does not dedupe against pruned work`() = runTest {
        val first = manager.startUrlImport("https://example.com/r")
        workInfosById.clear() // WorkManager pruned the finished work

        val second = manager.startUrlImport("https://example.com/r")

        assertNotEquals(first.importId, second.importId)
        assertEquals(PendingImportEntity.STATUS_FAILED, pendingImportsStore[first.importId]!!.status)
        assertEquals(PendingImportEntity.STATUS_PENDING, pendingImportsStore[second.importId]!!.status)
    }

    @Test
    fun `startUrlImport does not dedupe against finished work`() = runTest {
        val first = manager.startUrlImport("https://example.com/r")
        workInfosById[first.workId] = WorkInfo(first.workId, WorkInfo.State.SUCCEEDED, emptySet())

        val second = manager.startUrlImport("https://example.com/r")

        assertNotEquals(first.importId, second.importId)
        assertTrue(first.importId !in pendingImportsStore)
    }

    @Test
    fun `concurrent startUrlImport calls start one import`() = runTest {
        val results = (1..3).map { async { manager.startUrlImport("https://example.com/r") } }.awaitAll()

        assertEquals(1, results.map { it.importId }.distinct().size)
        assertEquals(1, pendingImportsStore.size)
    }

    @Test
    fun `startup reconciles rows whose work is missing or finished`() = runTest {
        val missingWork = UUID.randomUUID()
        val succeededWork = UUID.randomUUID()
        val runningWork = UUID.randomUUID()
        workInfosById[succeededWork] = WorkInfo(succeededWork, WorkInfo.State.SUCCEEDED, emptySet())
        workInfosById[runningWork] = WorkInfo(runningWork, WorkInfo.State.RUNNING, emptySet())
        listOf(
            "missing" to missingWork.toString(),
            "succeeded" to succeededWork.toString(),
            "running" to runningWork.toString(),
            "never-enqueued" to null
        ).forEach { (id, workId) ->
            pendingImportsStore[id] = PendingImportEntity(
                id = id, url = "https://example.com/$id", name = id, imageUrl = null,
                status = PendingImportEntity.STATUS_PENDING, workManagerId = workId,
                errorMessage = null, createdAt = 0L
            )
        }
        pendingImportsFlow.value = pendingImportsStore.values.toList()

        createManager()
        testScope.advanceUntilIdle()

        assertEquals(PendingImportEntity.STATUS_FAILED, pendingImportsStore["missing"]!!.status)
        assertEquals(InProgressRecipeManager.INTERRUPTED_MESSAGE, pendingImportsStore["missing"]!!.errorMessage)
        assertEquals(PendingImportEntity.STATUS_FAILED, pendingImportsStore["never-enqueued"]!!.status)
        assertTrue("succeeded" !in pendingImportsStore)
        assertEquals(PendingImportEntity.STATUS_PENDING, pendingImportsStore["running"]!!.status)
    }
}
