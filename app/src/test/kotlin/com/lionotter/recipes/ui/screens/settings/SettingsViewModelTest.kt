package com.lionotter.recipes.ui.screens.settings

import app.cash.turbine.test
import com.google.firebase.auth.AuthCredential
import com.lionotter.recipes.R
import com.lionotter.recipes.data.remote.AccountDeletionService
import com.lionotter.recipes.data.remote.AccountMigrationService
import com.lionotter.recipes.data.remote.AuthService
import com.lionotter.recipes.data.remote.AuthState
import com.lionotter.recipes.data.repository.ImportDebugRepository
import com.lionotter.recipes.domain.model.AiModels
import com.lionotter.recipes.domain.model.TagBarSize
import com.lionotter.recipes.domain.model.ThemeMode
import com.lionotter.recipes.domain.model.UnitSystem
import com.lionotter.recipes.domain.port.AppSettings
import com.lionotter.recipes.messages.UserMessage
import com.lionotter.recipes.testutil.AccountFakes
import com.lionotter.recipes.testutil.FakeAppSettings
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val settings = FakeAppSettings(apiKey = null, unitSystem = UnitSystem.CUSTOMARY).apply {
        weightUnitSystemFlow.value = UnitSystem.METRIC
    }
    private lateinit var importDebugRepository: ImportDebugRepository
    private lateinit var authService: AuthService
    private lateinit var accountMigrationService: AccountMigrationService
    private lateinit var viewModel: SettingsViewModel
    private val testDispatcher = StandardTestDispatcher()
    private val accounts = AccountFakes(AuthState.Google(uid = "google-uid", email = "user@example.com"))
    private val accountLifecycle = accounts.lifecycle(emptySet(), testDispatcher)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        importDebugRepository = mockk()
        authService = mockk()
        accountMigrationService = mockk()
        every { authService.authState } returns accounts.auth.authState
        viewModel = SettingsViewModel(settings, importDebugRepository, authService, accountLifecycle, accountMigrationService, {})
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
        viewModel.onApiKeyInputChange("sk-ant-valid-test-key")
        viewModel.saveApiKey()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("sk-ant-valid-test-key", settings.apiKeyFlow.value)
        assertNull(viewModel.apiKeyError.value)
    }

    @Test
    fun `saveApiKey failure shows a localized error`() = runTest {
        settings.writeError = IllegalStateException("keystore broken")
        viewModel.onApiKeyInputChange("sk-ant-valid-test-key")
        viewModel.saveApiKey()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(UserMessage(R.string.error_api_key_save), viewModel.apiKeyError.value)
    }

    @Test
    fun `saveApiKey clears input on success`() = runTest {
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
        viewModel.onApiKeyInputChange("  sk-ant-valid-test-key  ")
        viewModel.saveApiKey()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("sk-ant-valid-test-key", settings.apiKeyFlow.value)
    }

    @Test
    fun `clearApiKey clears the stored key`() = runTest {
        settings.apiKeyFlow.value = "sk-ant-stored-key"
        viewModel.clearApiKey()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(settings.apiKeyFlow.value)
    }

    @Test
    fun `setAiModel saves the model`() = runTest {
        viewModel.setAiModel("claude-3-sonnet")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("claude-3-sonnet", settings.aiModelFlow.value)
    }

    @Test
    fun `setVolumeKeysScroll saves the choice`() = runTest {
        viewModel.setVolumeKeysScroll(true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(true, settings.volumeKeysScrollFlow.value)
    }

    @Test
    fun `setThemeMode saves e-ink`() = runTest {
        viewModel.setThemeMode(ThemeMode.EINK)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ThemeMode.EINK, settings.themeModeFlow.value)
    }

    @Test
    fun `a failed setting write is reported once`() = runTest {
        settings.writeError = IllegalStateException("disk full")
        viewModel.setThemeMode(ThemeMode.DARK)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(UserMessage(R.string.settings_save_failed), viewModel.events.first())
    }

    @Test
    fun `settings stay Loading until read, then show the stored values`() = runTest {
        val gate = MutableStateFlow(false)
        val slowSettings = object : AppSettings by settings {
            override val settings = gate.filter { it }.flatMapLatest { this@SettingsViewModelTest.settings.settings }
        }
        val viewModel = SettingsViewModel(slowSettings, importDebugRepository, authService, accountLifecycle, accountMigrationService, {})
        viewModel.uiState.test {
            assertEquals(SettingsUiState.Loading, awaitItem())
            expectNoEvents()

            gate.value = true
            val loaded = (awaitItem() as SettingsUiState.Loaded).settings
            assertNull(loaded.anthropicApiKey)
            assertEquals(UnitSystem.CUSTOMARY, loaded.volumeUnitSystem)
            assertEquals(UnitSystem.METRIC, loaded.weightUnitSystem)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `settings reflect stored changes`() = runTest {
        viewModel.uiState.test {
            assertEquals(SettingsUiState.Loading, awaitItem())
            assertEquals(AiModels.DEFAULT_MODEL, (awaitItem() as SettingsUiState.Loaded).settings.aiModel)

            settings.aiModelFlow.value = "claude-opus-5-5"
            settings.apiKeyFlow.value = "sk-ant-stored-key"
            testDispatcher.scheduler.advanceUntilIdle()

            val loaded = (expectMostRecentItem() as SettingsUiState.Loaded).settings
            assertEquals("claude-opus-5-5", loaded.aiModel)
            assertEquals("sk-ant-stored-key", loaded.anthropicApiKey)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setTagBarSize saves the size, kept within range`() = runTest {
        viewModel.setTagBarSize(15)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(15, settings.tagPreferences.tagBarSizeFlow.value)

        viewModel.setTagBarSize(100)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(TagBarSize.MAX, settings.tagPreferences.tagBarSizeFlow.value)
    }

    @Test
    fun `setThinkingEnabled saves the setting`() = runTest {
        settings.thinkingFlow.value = true
        viewModel.setThinkingEnabled(false)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(settings.thinkingFlow.value)
    }

    @Test
    fun `sign out with unsynced changes asks for confirmation before forcing`() = runTest {
        accounts.firestore.pendingWritesSynced = false

        viewModel.signOut()
        advanceUntilIdle()
        assertTrue(viewModel.showUnsyncedSignOutWarning.value)
        assertTrue(viewModel.authState.value is AuthState.Google)

        viewModel.signOut(force = true)
        advanceUntilIdle()
        assertFalse(viewModel.showUnsyncedSignOutWarning.value)
        assertTrue(viewModel.authState.value is AuthState.Guest)
    }

    @Test
    fun `only one account operation runs at a time`() = runTest {
        val pendingWrites = CompletableDeferred<Boolean>()
        accounts.firestore.pendingWritesResult = pendingWrites

        viewModel.signOut()
        advanceUntilIdle()
        assertEquals(AccountOp.SIGNING_OUT, viewModel.busyOp.value)
        assertFalse(viewModel.beginGoogleSignIn())
        assertFalse(viewModel.beginDeleteAccount())

        pendingWrites.complete(true)
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

        assertTrue(viewModel.beginDeleteAccount())
        assertEquals(AccountOp.DELETING_ACCOUNT, viewModel.busyOp.value)
        viewModel.deleteAccount(CredentialResult.Obtained(credential))
        advanceUntilIdle()

        assertEquals(UserMessage(R.string.delete_account_success), viewModel.events.first())
        assertEquals(listOf(credential), accounts.remote.credentials)
        assertTrue(viewModel.authState.value is AuthState.Guest)
        assertNull(viewModel.busyOp.value)
    }

    @Test
    fun `failed account deletion explains why`() = runTest {
        accounts.remote.failure = AccountDeletionService.OfflineException()

        assertTrue(viewModel.beginDeleteAccount())
        viewModel.deleteAccount(CredentialResult.Failed(IllegalStateException("no credential")))
        advanceUntilIdle()

        assertEquals(UserMessage(R.string.delete_account_offline), viewModel.events.first())
        assertTrue(viewModel.authState.value is AuthState.Google)
    }

    @Test
    fun `a failed sign out is reported`() = runTest {
        accounts.auth.signOutFailure = IllegalStateException("boom")
        viewModel.signOut()
        advanceUntilIdle()

        assertEquals(UserMessage(R.string.error_sign_out), viewModel.events.first())
        assertNull(viewModel.busyOp.value)
    }
}
