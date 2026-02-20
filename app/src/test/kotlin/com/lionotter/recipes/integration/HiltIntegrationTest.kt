package com.lionotter.recipes.integration

import com.lionotter.recipes.data.local.MealPlanDao
import com.lionotter.recipes.data.local.RecipeDao
import com.lionotter.recipes.data.local.RecipeDatabase
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.repository.MealPlanRepository
import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.domain.model.StartOfWeek
import com.lionotter.recipes.domain.model.ThemeMode
import com.lionotter.recipes.domain.model.UnitSystem
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * Base class for full-stack Hilt integration tests that exercise the real
 * DAO → Repository → ViewModel pipeline backed by an in-memory Room database.
 *
 * External services (network, WorkManager, encryption) are replaced with test doubles
 * via `@TestInstallIn` modules and `@BindValue`.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34])
abstract class HiltIntegrationTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var db: RecipeDatabase

    @Inject
    lateinit var recipeDao: RecipeDao

    @Inject
    lateinit var mealPlanDao: MealPlanDao

    @Inject
    lateinit var recipeRepository: RecipeRepository

    @Inject
    lateinit var mealPlanRepository: MealPlanRepository

    /**
     * Mock [SettingsDataStore] to avoid Tink/Android Keystore initialization,
     * which doesn't work under Robolectric.
     */
    @BindValue
    val settingsDataStore: SettingsDataStore = mockk(relaxed = true) {
        every { anthropicApiKey } returns MutableStateFlow(null)
        every { aiModel } returns MutableStateFlow("claude-sonnet-4-20250514")
        every { extendedThinkingEnabled } returns MutableStateFlow(true)
        every { keepScreenOn } returns MutableStateFlow(true)
        every { themeMode } returns MutableStateFlow(ThemeMode.AUTO)
        every { volumeUnitSystem } returns MutableStateFlow(UnitSystem.CUSTOMARY)
        every { weightUnitSystem } returns MutableStateFlow(UnitSystem.CUSTOMARY)
        every { groceryVolumeUnitSystem } returns MutableStateFlow(UnitSystem.CUSTOMARY)
        every { groceryWeightUnitSystem } returns MutableStateFlow(UnitSystem.CUSTOMARY)
        every { startOfWeek } returns MutableStateFlow(StartOfWeek.LOCALE_DEFAULT)
        every { importDebuggingEnabled } returns MutableStateFlow(false)
    }

    @Before
    fun baseSetup() {
        hiltRule.inject()
    }

    @After
    fun baseTeardown() {
        db.close()
    }
}
