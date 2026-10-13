package com.lionotter.recipes.ui.screens.settings

import app.cash.turbine.test
import com.google.firebase.auth.AuthCredential
import com.lionotter.recipes.R
import com.lionotter.recipes.data.remote.AccountDeletionService
import com.lionotter.recipes.data.remote.AccountMigrationService
import com.lionotter.recipes.data.remote.AuthService
import com.lionotter.recipes.data.remote.AuthState
import com.lionotter.recipes.data.repository.ImportDebugRepository
import com.lionotter.recipes.domain.model.AiModel
import com.lionotter.recipes.domain.model.AiProviders
import com.lionotter.recipes.domain.model.ModelRole
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

    private val anthropicKey get() = viewModel.apiKeyFields.single { it.provider == AiProviders.ANTHROPIC }
    private val openAiKey get() = viewModel.apiKeyFields.single { it.provider == AiProviders.OPENAI }

    @Test
    fun `there is a key field for every provider`() {
        assertEquals(AiProviders.ALL, viewModel.apiKeyFields.map { it.provider })
    }

    @Test
    fun `an Anthropic key in the OpenAI field shows an error`() = runTest {
        openAiKey.onInputChange("sk-ant-api03-abc")
        openAiKey.save()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(UserMessage(R.string.error_api_key_other_provider, "Anthropic", "OpenAI"), openAiKey.error.value)
        assertNull(settings.openAiApiKeyFlow.value)
    }

    @Test
    fun `initial apiKeyInput is empty`() = runTest {
        anthropicKey.input.test {
            assertEquals("", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initially there is no API key error`() = runTest {
        assertNull(anthropicKey.error.value)
    }

    @Test
    fun `onApiKeyInputChange updates apiKeyInput`() = runTest {
        anthropicKey.input.test {
            assertEquals("", awaitItem())

            anthropicKey.onInputChange("sk-ant-test-key")
            assertEquals("sk-ant-test-key", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `saveApiKey with empty key shows an error`() = runTest {
        anthropicKey.onInputChange("   ")
        anthropicKey.save()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(UserMessage(R.string.error_api_key_empty), anthropicKey.error.value)
    }

    @Test
    fun `saveApiKey with invalid format shows an error until the key is edited`() = runTest {
        anthropicKey.onInputChange("invalid-key-format")
        anthropicKey.save()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(UserMessage(R.string.error_api_key_prefix, "sk-ant-"), anthropicKey.error.value)

        anthropicKey.onInputChange("sk-ant-")
        assertNull(anthropicKey.error.value)
    }

    @Test
    fun `saveApiKey with valid key saves it`() = runTest {
        anthropicKey.onInputChange("sk-ant-valid-test-key")
        anthropicKey.save()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("sk-ant-valid-test-key", settings.apiKeyFlow.value)
        assertNull(anthropicKey.error.value)
    }

    @Test
    fun `saveApiKey failure shows a localized error`() = runTest {
        settings.writeError = IllegalStateException("keystore broken")
        anthropicKey.onInputChange("sk-ant-valid-test-key")
        anthropicKey.save()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(UserMessage(R.string.error_api_key_save), anthropicKey.error.value)
    }

    @Test
    fun `saveApiKey clears input on success`() = runTest {
        anthropicKey.input.test {
            assertEquals("", awaitItem())

            anthropicKey.onInputChange("sk-ant-valid-test-key")
            assertEquals("sk-ant-valid-test-key", awaitItem())

            anthropicKey.save()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `saveApiKey trims whitespace from key`() = runTest {
        anthropicKey.onInputChange("  sk-ant-valid-test-key  ")
        anthropicKey.save()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("sk-ant-valid-test-key", settings.apiKeyFlow.value)
    }

    @Test
    fun `clearApiKey clears the stored key`() = runTest {
        settings.apiKeyFlow.value = "sk-ant-stored-key"
        viewModel.clearApiKey(AiProviders.ANTHROPIC_ID)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(settings.apiKeyFlow.value)
    }

    @Test
    fun `setSelectedModel saves the model for the role`() = runTest {
        viewModel.setSelectedModel(ModelRole.IMPORT, AiProviders.CLAUDE_OPUS)
        viewModel.setSelectedModel(ModelRole.IMAGE, AiProviders.GPT_IMAGE_SUNBURST)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(AiProviders.CLAUDE_OPUS, settings.aiModelFlow.value)
        assertEquals(AiProviders.GPT_IMAGE_SUNBURST, settings.imageModelFlow.value)
        assertNull(settings.editModelFlow.value)
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
            assertEquals(emptyMap<String, String>(), loaded.apiKeys)
            assertEquals(UnitSystem.CUSTOMARY, loaded.volumeUnitSystem)
            assertEquals(UnitSystem.METRIC, loaded.weightUnitSystem)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `settings reflect stored changes`() = runTest {
        viewModel.uiState.test {
            assertEquals(SettingsUiState.Loading, awaitItem())
            assertEquals(emptyMap<ModelRole, AiModel>(), (awaitItem() as SettingsUiState.Loaded).settings.models)

            settings.aiModelFlow.value = AiProviders.CLAUDE_OPUS
            settings.apiKeyFlow.value = "sk-ant-stored-key"
            testDispatcher.scheduler.advanceUntilIdle()

            val loaded = (expectMostRecentItem() as SettingsUiState.Loaded).settings
            assertEquals(AiProviders.CLAUDE_OPUS, loaded.models[ModelRole.IMPORT]?.id)
            assertEquals(AiProviders.CLAUDE_HAIKU, loaded.models[ModelRole.EDIT]?.id)
            assertNull(loaded.models[ModelRole.IMAGE])
            assertEquals(mapOf(AiProviders.ANTHROPIC_ID to "sk-ant-stored-key"), loaded.apiKeys)
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
