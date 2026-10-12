package com.lionotter.recipes.ui.screens.editrecipe

import android.net.Uri
import android.util.Log
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import androidx.lifecycle.SavedStateHandle
import com.lionotter.recipes.worker.PendingWork
import com.lionotter.recipes.worker.RecipeEditWork
import com.lionotter.recipes.worker.RecipeWorkScheduler
import com.lionotter.recipes.worker.WorkStatus
import java.io.IOException
import java.util.UUID
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.remote.ImageDownloadService
import com.lionotter.recipes.data.repository.IRecipeRepository
import com.lionotter.recipes.domain.model.Recipe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class EditRecipeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var recipeRepository: IRecipeRepository
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var imageDownloadService: ImageDownloadService

    private val recipe = Recipe(
        id = "r1",
        name = "Pasta",
        sourceUrl = "https://example.com",
        imageUrl = "file:///old.jpg",
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0)
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        recipeRepository = mockk()
        settingsDataStore = mockk()
        imageDownloadService = mockk()
        every { recipeRepository.getRecipeById("r1") } returns MutableStateFlow(recipe)
        coEvery { recipeRepository.getRecipeByIdOnce("r1") } returns recipe
        every { recipeRepository.setImageUrl(any(), any()) } just runs
        every { recipeRepository.updateTitleAndUrl(any(), any(), any()) } just runs
        every { settingsDataStore.editModel } returns flowOf("model")
        every { settingsDataStore.thinkingEnabled } returns flowOf(false)
        coEvery { imageDownloadService.deleteLocalImage(any()) } just runs
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        handle: SavedStateHandle = SavedStateHandle(mapOf("recipeId" to "r1")),
        scheduler: RecipeWorkScheduler = mockk(relaxed = true)
    ) = EditRecipeViewModel(handle, recipeRepository, settingsDataStore, imageDownloadService, scheduler, TestScope(UnconfinedTestDispatcher()))

    @Test
    fun `failure to start the AI edit shows an error instead of crashing`() = runTest {
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
        val scheduler = mockk<RecipeWorkScheduler>()
        every { scheduler.prepareEdit(any(), any(), any(), any(), any(), any()) } throws IOException("disk full")
        val viewModel = createViewModel(scheduler = scheduler)
        advanceUntilIdle()

        viewModel.setMarkdownText("changed")
        viewModel.saveEdits()

        val state = viewModel.editState.first { it is EditUiState.Error }
        assertTrue((state as EditUiState.Error).message.contains("disk full"))
        unmockkStatic(Log::class)
    }

    @Test
    fun `save as copy applies a pending image to the copy, not the original`() = runTest {
        val workId = UUID.randomUUID()
        val scheduler = mockk<RecipeWorkScheduler>()
        every { scheduler.prepareEdit("r1", any(), any(), any(), true, any()) } returns PendingWork(workId) {}
        every { scheduler.observeEdit(workId) } returns
            flowOf(WorkStatus.Finished(RecipeEditWork.Outcome.Success("r1", "Pasta", newRecipeId = "copy1")))
        coEvery { recipeRepository.getRecipeByIdOnce("copy1") } returns recipe.copy(id = "copy1")
        coEvery { imageDownloadService.storeImage("file:///new.jpg", "file:///old.jpg", "copy1") } returns "gs://new"
        val contentUri = mockk<Uri>()
        coEvery { imageDownloadService.saveImageFromContentUri(contentUri) } returns "file:///new.jpg"
        val viewModel = createViewModel(scheduler = scheduler)
        advanceUntilIdle()
        viewModel.onImageSelected(contentUri)
        viewModel.imageUrl.first { it == "file:///new.jpg" }

        viewModel.saveAsCopy()
        assertEquals(EditUiState.Success(newRecipeId = "copy1"), viewModel.editState.first { it is EditUiState.Success })
        advanceUntilIdle()

        verify { recipeRepository.setImageUrl("copy1", "gs://new") }
        verify(exactly = 0) { recipeRepository.setImageUrl("r1", any()) }
        coVerify(exactly = 0) { imageDownloadService.deleteLocalImage("file:///new.jpg") }
    }

    @Test
    fun `edit that fails for a missing API key says so`() = runTest {
        val workId = UUID.randomUUID()
        val scheduler = mockk<RecipeWorkScheduler>()
        every { scheduler.prepareEdit(any(), any(), any(), any(), any(), any()) } returns PendingWork(workId) {}
        every { scheduler.observeEdit(workId) } returns
            flowOf(WorkStatus.Finished(RecipeEditWork.Outcome.NoApiKey("API key not configured", "r1")))
        val viewModel = createViewModel(scheduler = scheduler)
        advanceUntilIdle()

        viewModel.setMarkdownText("changed")
        viewModel.saveEdits()

        assertEquals(EditUiState.NoApiKey, viewModel.editState.first { it == EditUiState.NoApiKey })
    }

    @Test
    fun `a running edit is shown again after process death`() = runTest {
        val workId = UUID.randomUUID()
        val scheduler = mockk<RecipeWorkScheduler>()
        every { scheduler.observeEdit(workId) } returns flowOf(WorkStatus.Running(RecipeEditWork.Progress.Saving))
        val handle = SavedStateHandle(mapOf("recipeId" to "r1", "edit_work_id" to workId.toString()))

        val viewModel = createViewModel(handle, scheduler)

        assertEquals(EditUiState.Loading("Saving recipe..."), viewModel.editState.first { it is EditUiState.Loading })
    }

    @Test
    fun `cancelling a running edit returns to editing`() = runTest {
        val workId = UUID.randomUUID()
        val status = MutableStateFlow<WorkStatus<RecipeEditWork.Progress, RecipeEditWork.Outcome>>(WorkStatus.Queued)
        val scheduler = mockk<RecipeWorkScheduler>()
        every { scheduler.prepareEdit(any(), any(), any(), any(), any(), any()) } returns PendingWork(workId) {}
        every { scheduler.observeEdit(workId) } returns status
        every { scheduler.cancel(workId) } answers { status.value = WorkStatus.Cancelled }
        val viewModel = createViewModel(scheduler = scheduler)
        advanceUntilIdle()
        viewModel.setMarkdownText("changed")
        viewModel.saveEdits()
        viewModel.editState.first { it == EditUiState.Loading("Preparing...") }

        viewModel.cancelProcessing()

        assertEquals(EditUiState.Idle, viewModel.editState.first { it == EditUiState.Idle })
    }

    @Test
    fun `fields are populated from the recipe and start clean`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals("Pasta", viewModel.title.value)
        assertFalse(viewModel.hasUnsavedChanges.value)

        viewModel.setTitle("Better Pasta")
        advanceUntilIdle()
        assertTrue(viewModel.hasUnsavedChanges.value)
    }

    @Test
    fun `edited fields survive recreation from saved state`() = runTest {
        val handle = SavedStateHandle(mapOf("recipeId" to "r1"))
        val first = createViewModel(handle)
        advanceUntilIdle()
        first.setTitle("Edited")

        val second = createViewModel(handle)
        advanceUntilIdle()

        assertEquals("Edited", second.title.value)
        assertTrue(second.hasUnsavedChanges.value)
    }

    @Test
    fun `picked image is pending until save`() = runTest {
        val contentUri = mockk<Uri>()
        coEvery { imageDownloadService.saveImageFromContentUri(contentUri) } returns "file:///new.jpg"
        coEvery { imageDownloadService.storeImage("file:///new.jpg", "file:///old.jpg", "r1") } returns "gs://new"
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onImageSelected(contentUri)
        // The copy runs on Dispatchers.IO, so wait for it rather than advancing virtual time
        viewModel.imageUrl.first { it == "file:///new.jpg" }
        advanceUntilIdle()
        assertTrue(viewModel.hasUnsavedChanges.value)
        verify(exactly = 0) { recipeRepository.setImageUrl(any(), any()) }

        viewModel.saveEdits()
        advanceUntilIdle()

        verify { recipeRepository.setImageUrl("r1", "gs://new") }
        assertTrue(viewModel.editState.value is EditUiState.Success)
    }

    @Test
    fun `removing image is applied on save`() = runTest {
        coEvery { imageDownloadService.cleanupImage("file:///old.jpg", "r1") } just runs
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.removeImage()
        advanceUntilIdle()
        assertEquals(null, viewModel.imageUrl.value)
        verify(exactly = 0) { recipeRepository.setImageUrl(any(), any()) }

        viewModel.saveEdits()
        advanceUntilIdle()

        coVerify { imageDownloadService.cleanupImage("file:///old.jpg", "r1") }
        verify { recipeRepository.setImageUrl("r1", null) }
    }
}
