package com.lionotter.recipes.ui.screens.editrecipe

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.work.WorkManager
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
    private lateinit var workManager: WorkManager
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
        workManager = mockk()
        imageDownloadService = mockk()
        every { recipeRepository.getRecipeById("r1") } returns MutableStateFlow(recipe)
        coEvery { recipeRepository.getRecipeByIdOnce("r1") } returns recipe
        every { recipeRepository.setImageUrl(any(), any()) } just runs
        every { recipeRepository.updateTitleAndUrl(any(), any(), any()) } just runs
        every { settingsDataStore.editModel } returns flowOf("model")
        every { settingsDataStore.thinkingEnabled } returns flowOf(false)
        every { workManager.getWorkInfosByTagFlow(any()) } returns flowOf(emptyList())
        every { imageDownloadService.deleteLocalImage(any()) } just runs
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(handle: SavedStateHandle = SavedStateHandle(mapOf("recipeId" to "r1"))) =
        EditRecipeViewModel(handle, recipeRepository, settingsDataStore, workManager, imageDownloadService, mockk(relaxed = true))

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
        every { imageDownloadService.saveImageFromContentUri(contentUri) } returns "file:///new.jpg"
        coEvery { imageDownloadService.storeImage("file:///new.jpg", "file:///old.jpg") } returns "gs://new"
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
        coEvery { imageDownloadService.cleanupImage("file:///old.jpg") } just runs
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.removeImage()
        advanceUntilIdle()
        assertEquals(null, viewModel.imageUrl.value)
        verify(exactly = 0) { recipeRepository.setImageUrl(any(), any()) }

        viewModel.saveEdits()
        advanceUntilIdle()

        coVerify { imageDownloadService.cleanupImage("file:///old.jpg") }
        verify { recipeRepository.setImageUrl("r1", null) }
    }
}
