package com.lionotter.recipes.ui.screens.settings

import app.cash.turbine.test
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.remote.AnthropicService
import com.lionotter.recipes.data.repository.ImportDebugRepository
import com.lionotter.recipes.domain.model.StartOfWeek
import com.lionotter.recipes.domain.model.ThemeMode
import com.lionotter.recipes.domain.model.UnitSystem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var importDebugRepository: ImportDebugRepository
    private lateinit var viewModel: SettingsViewModel
    private val testDispatcher = StandardTestDispatcher()

    private val apiKeyFlow = MutableStateFlow<String?>(null)
    private val aiModelFlow = MutableStateFlow(AnthropicService.DEFAULT_MODEL)
    private val editModelFlow = MutableStateFlow(AnthropicService.DEFAULT_EDIT_MODEL)
    private val thinkingEnabledFlow = MutableStateFlow(true)
    private val keepScreenOnFlow = MutableStateFlow(true)
    private val themeModeFlow = MutableStateFlow(ThemeMode.AUTO)
    private val importDebuggingEnabledFlow = MutableStateFlow(false)
    private val volumeUnitSystemFlow = MutableStateFlow(UnitSystem.CUSTOMARY)
    private val weightUnitSystemFlow = MutableStateFlow(UnitSystem.METRIC)
    private val groceryVolumeUnitSystemFlow = MutableStateFlow(UnitSystem.CUSTOMARY)
    private val groceryWeightUnitSystemFlow = MutableStateFlow(UnitSystem.METRIC)
    private val startOfWeekFlow = MutableStateFlow(StartOfWeek.LOCALE_DEFAULT)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        settingsDataStore = mockk()
        importDebugRepository = mockk()
        every { settingsDataStore.anthropicApiKey } returns apiKeyFlow
        every { settingsDataStore.aiModel } returns aiModelFlow
        every { settingsDataStore.editModel } returns editModelFlow
        every { settingsDataStore.thinkingEnabled } returns thinkingEnabledFlow
        every { settingsDataStore.keepScreenOn } returns keepScreenOnFlow
        every { settingsDataStore.themeMode } returns themeModeFlow
        every { settingsDataStore.importDebuggingEnabled } returns importDebuggingEnabledFlow
        every { settingsDataStore.volumeUnitSystem } returns volumeUnitSystemFlow
        every { settingsDataStore.weightUnitSystem } returns weightUnitSystemFlow
        every { settingsDataStore.groceryVolumeUnitSystem } returns groceryVolumeUnitSystemFlow
        every { settingsDataStore.groceryWeightUnitSystem } returns groceryWeightUnitSystemFlow
        every { settingsDataStore.startOfWeek } returns startOfWeekFlow
        viewModel = SettingsViewModel(settingsDataStore, importDebugRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial apiKeyInput is empty`() = runTest {
        viewModel.apiKeyInput.test {
            assertEquals("", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initial saveState is Idle`() = runTest {
        viewModel.saveState.test {
            assertTrue(awaitItem() is SettingsViewModel.SaveState.Idle)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onApiKeyInputChange updates apiKeyInput`() = runTest {
        viewModel.apiKeyInput.test {
            assertEquals("", awaitItem())

            viewModel.onApiKeyInputChange("sk-ant-test-key")
            assertEquals("sk-ant-test-key", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `saveApiKey with empty key sets error state`() = runTest {
        viewModel.saveState.test {
            assertTrue(awaitItem() is SettingsViewModel.SaveState.Idle)

            viewModel.onApiKeyInputChange("   ")
            viewModel.saveApiKey()
            testDispatcher.scheduler.advanceUntilIdle()

            val state = awaitItem()
            assertTrue(state is SettingsViewModel.SaveState.Error)
            assertEquals("API key cannot be empty", (state as SettingsViewModel.SaveState.Error).message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `saveApiKey with invalid format sets error state`() = runTest {
        viewModel.saveState.test {
            assertTrue(awaitItem() is SettingsViewModel.SaveState.Idle)

            viewModel.onApiKeyInputChange("invalid-key-format")
            viewModel.saveApiKey()
            testDispatcher.scheduler.advanceUntilIdle()

            val state = awaitItem()
            assertTrue(state is SettingsViewModel.SaveState.Error)
            assertTrue((state as SettingsViewModel.SaveState.Error).message.contains("sk-ant-"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `saveApiKey with valid key saves and sets success state`() = runTest {
        coEvery { settingsDataStore.setAnthropicApiKey(any()) } just runs

        viewModel.saveState.test {
            assertTrue(awaitItem() is SettingsViewModel.SaveState.Idle)

            viewModel.onApiKeyInputChange("sk-ant-valid-test-key")
            viewModel.saveApiKey()
            testDispatcher.scheduler.advanceUntilIdle()

            val state = awaitItem()
            assertTrue(state is SettingsViewModel.SaveState.Success)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { settingsDataStore.setAnthropicApiKey("sk-ant-valid-test-key") }
    }

    @Test
    fun `saveApiKey clears input on success`() = runTest {
        coEvery { settingsDataStore.setAnthropicApiKey(any()) } just runs

        viewModel.apiKeyInput.test {
            assertEquals("", awaitItem())

            viewModel.onApiKeyInputChange("sk-ant-valid-test-key")
            assertEquals("sk-ant-valid-test-key", awaitItem())

            viewModel.saveApiKey()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `saveApiKey trims whitespace from key`() = runTest {
        coEvery { settingsDataStore.setAnthropicApiKey(any()) } just runs

        viewModel.onApiKeyInputChange("  sk-ant-valid-test-key  ")
        viewModel.saveApiKey()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { settingsDataStore.setAnthropicApiKey("sk-ant-valid-test-key") }
    }

    @Test
    fun `clearApiKey calls datastore and resets state`() = runTest {
        coEvery { settingsDataStore.clearAnthropicApiKey() } just runs

        viewModel.saveState.test {
            assertTrue(awaitItem() is SettingsViewModel.SaveState.Idle)

            viewModel.clearApiKey()
            testDispatcher.scheduler.advanceUntilIdle()

            // State should remain or return to Idle
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { settingsDataStore.clearAnthropicApiKey() }
    }

    @Test
    fun `setAiModel calls datastore`() = runTest {
        coEvery { settingsDataStore.setAiModel(any()) } just runs

        viewModel.setAiModel("claude-3-sonnet")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { settingsDataStore.setAiModel("claude-3-sonnet") }
    }

    @Test
    fun `resetSaveState sets state to Idle`() = runTest {
        coEvery { settingsDataStore.setAnthropicApiKey(any()) } just runs

        viewModel.saveState.test {
            assertTrue(awaitItem() is SettingsViewModel.SaveState.Idle)

            // First set success state
            viewModel.onApiKeyInputChange("sk-ant-valid-key")
            viewModel.saveApiKey()
            testDispatcher.scheduler.advanceUntilIdle()
            assertTrue(awaitItem() is SettingsViewModel.SaveState.Success)

            // Then reset
            viewModel.resetSaveState()
            assertTrue(awaitItem() is SettingsViewModel.SaveState.Idle)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `apiKey flow reflects datastore value`() = runTest {
        viewModel.apiKey.test {
            // Initial value from flow
            assertEquals(null, awaitItem())

            // Update the underlying flow
            apiKeyFlow.value = "sk-ant-stored-key"
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("sk-ant-stored-key", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setThinkingEnabled calls datastore`() = runTest {
        coEvery { settingsDataStore.setThinkingEnabled(any()) } just runs

        viewModel.setThinkingEnabled(false)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { settingsDataStore.setThinkingEnabled(false) }
    }

    @Test
    fun `thinkingEnabled flow reflects datastore value`() = runTest {
        viewModel.thinkingEnabled.test {
            // Initial value (default: true)
            assertEquals(true, awaitItem())

            // Update the underlying flow
            thinkingEnabledFlow.value = false
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `aiModel flow reflects datastore value`() = runTest {
        viewModel.aiModel.test {
            // Initial value (default model)
            assertEquals(AnthropicService.DEFAULT_MODEL, awaitItem())

            // Update the underlying flow
            aiModelFlow.value = "claude-3-opus"
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("claude-3-opus", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
