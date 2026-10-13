package com.lionotter.recipes.ui.screens.settings

import org.junit.Assert.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import io.mockk.unmockkStatic
import io.mockk.mockkStatic
import com.lionotter.recipes.messages.UserMessage
import com.lionotter.recipes.R
import com.google.firebase.auth.AuthCredential
import android.util.Log
import org.junit.Assert.assertFalse
import kotlinx.coroutines.test.advanceUntilIdle
import com.lionotter.recipes.data.remote.SignOutResult
import app.cash.turbine.test
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.remote.AnthropicService
import com.lionotter.recipes.data.remote.AccountMigrationService
import com.lionotter.recipes.data.remote.AuthService
import com.lionotter.recipes.data.remote.AuthState
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
    private lateinit var authService: AuthService
    private lateinit var accountMigrationService: AccountMigrationService
    private lateinit var viewModel: SettingsViewModel
    private val testDispatcher = StandardTestDispatcher()

    private val apiKeyFlow = MutableStateFlow<String?>(null)
    private val aiModelFlow = MutableStateFlow(AnthropicService.DEFAULT_MODEL)
    private val editModelFlow = MutableStateFlow(AnthropicService.DEFAULT_EDIT_MODEL)
    private val thinkingEnabledFlow = MutableStateFlow(SettingsDataStore.DEFAULT_THINKING_ENABLED)
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
        authService = mockk()
        accountMigrationService = mockk()
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
        every { authService.authState } returns MutableStateFlow<AuthState>(AuthState.Guest(uid = "test-guest"))
        viewModel = SettingsViewModel(settingsDataStore, importDebugRepository, authService, accountMigrationService, {})
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
    fun `initially there is no API key error`() = runTest {
        assertNull(viewModel.apiKeyError.value)
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
    fun `saveApiKey with empty key shows an error`() = runTest {
        viewModel.onApiKeyInputChange("   ")
        viewModel.saveApiKey()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(UserMessage(R.string.error_api_key_empty), viewModel.apiKeyError.value)
    }

    @Test
    fun `saveApiKey with invalid format shows an error until the key is edited`() = runTest {
        viewModel.onApiKeyInputChange("invalid-key-format")
        viewModel.saveApiKey()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(UserMessage(R.string.error_api_key_prefix, "sk-ant-"), viewModel.apiKeyError.value)

        viewModel.onApiKeyInputChange("sk-ant-")
        assertNull(viewModel.apiKeyError.value)
    }

    @Test
    fun `saveApiKey with valid key saves it`() = runTest {
        coEvery { settingsDataStore.setAnthropicApiKey(any()) } just runs

        viewModel.onApiKeyInputChange("sk-ant-valid-test-key")
        viewModel.saveApiKey()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { settingsDataStore.setAnthropicApiKey("sk-ant-valid-test-key") }
        assertNull(viewModel.apiKeyError.value)
    }

    @Test
    fun `saveApiKey failure shows a localized error`() = runTest {
        coEvery { settingsDataStore.setAnthropicApiKey(any()) } throws IllegalStateException("keystore broken")
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
        try {
            viewModel.onApiKeyInputChange("sk-ant-valid-test-key")
            viewModel.saveApiKey()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(UserMessage(R.string.error_api_key_save), viewModel.apiKeyError.value)
        } finally {
            unmockkStatic(Log::class)
        }
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
    fun `clearApiKey calls datastore`() = runTest {
        coEvery { settingsDataStore.clearAnthropicApiKey() } just runs

        viewModel.clearApiKey()
        testDispatcher.scheduler.advanceUntilIdle()

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
    fun `a failed setting write is reported once`() = runTest {
        coEvery { settingsDataStore.setThemeMode(any()) } throws IllegalStateException("disk full")
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
        try {
            viewModel.setThemeMode(ThemeMode.DARK)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(UserMessage(R.string.settings_save_failed), viewModel.events.first())
        } finally {
            unmockkStatic(Log::class)
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
            // Initial value (default: false)
            assertEquals(false, awaitItem())

            // Update the underlying flow
            thinkingEnabledFlow.value = true
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(true, awaitItem())
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

    @Test
    fun `sign out with unsynced changes asks for confirmation before forcing`() = runTest {
        coEvery { authService.signOut(false) } returns SignOutResult.PendingWrites
        coEvery { authService.signOut(true) } returns SignOutResult.Success

        viewModel.signOut()
        advanceUntilIdle()
        assertTrue(viewModel.showUnsyncedSignOutWarning.value)

        viewModel.signOut(force = true)
        advanceUntilIdle()
        assertFalse(viewModel.showUnsyncedSignOutWarning.value)
        coVerify { authService.signOut(true) }
    }

    @Test
    fun `only one account operation runs at a time`() = runTest {
        val signOut = CompletableDeferred<SignOutResult>()
        coEvery { authService.signOut(false) } coAnswers { signOut.await() }

        viewModel.signOut()
        advanceUntilIdle()
        assertEquals(AccountOp.SIGNING_OUT, viewModel.busyOp.value)
        assertFalse(viewModel.beginGoogleSignIn())
        assertFalse(viewModel.beginDeleteAccount())

        signOut.complete(SignOutResult.Success)
        advanceUntilIdle()
        assertNull(viewModel.busyOp.value)
        assertTrue(viewModel.beginGoogleSignIn())
        assertEquals(AccountOp.SIGNING_IN, viewModel.busyOp.value)
    }

    @Test
    fun `a cancelled credential request ends the operation quietly`() = runTest {
        assertTrue(viewModel.beginDeleteAccount())
        viewModel.deleteAccount(CredentialResult.Cancelled)
        advanceUntilIdle()
        assertNull(viewModel.busyOp.value)

        assertTrue(viewModel.beginGoogleSignIn())
        viewModel.completeGoogleSignIn(CredentialResult.Cancelled)
        advanceUntilIdle()
        assertNull(viewModel.busyOp.value)
        viewModel.events.test { expectNoEvents() }
    }

    @Test
    fun `account deletion reports the outcome`() = runTest {
        val credential = mockk<AuthCredential>()
        coEvery { authService.deleteAccountAndData(credential) } just runs

        assertTrue(viewModel.beginDeleteAccount())
        assertEquals(AccountOp.DELETING_ACCOUNT, viewModel.busyOp.value)
        viewModel.deleteAccount(CredentialResult.Obtained(credential))
        advanceUntilIdle()

        assertEquals(UserMessage(R.string.delete_account_success), viewModel.events.first())
        assertNull(viewModel.busyOp.value)
    }

    @Test
    fun `a failed sign out is reported`() = runTest {
        coEvery { authService.signOut(false) } throws IllegalStateException("boom")
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
        try {
            viewModel.signOut()
            advanceUntilIdle()

            assertEquals(UserMessage(R.string.error_sign_out), viewModel.events.first())
            assertNull(viewModel.busyOp.value)
        } finally {
            unmockkStatic(Log::class)
        }
    }
}
