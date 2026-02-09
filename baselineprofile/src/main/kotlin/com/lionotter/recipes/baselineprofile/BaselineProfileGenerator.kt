package com.lionotter.recipes.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates Baseline Profiles for the app by exercising critical user journeys.
 *
 * Run this test on a physical device or emulator to generate the profile:
 * ```
 * ./gradlew :baselineprofile:pixel6Api31BenchmarkAndroidTest
 * ```
 * or with a connected device:
 * ```
 * ./gradlew :app:generateBaselineProfile
 * ```
 *
 * The generated profile will be copied to `app/src/main/generated/baselineProfiles/`.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generateStartupProfile() {
        rule.collect(
            packageName = PACKAGE_NAME,
            includeInStartupProfile = true
        ) {
            startActivityAndWait()
        }
    }

    @Test
    fun generateRecipeListProfile() {
        rule.collect(
            packageName = PACKAGE_NAME
        ) {
            startActivityAndWait()

            // Scroll through the recipe list
            val list = device.findObject(By.scrollable(true))
            list?.let {
                it.setGestureMargin(device.displayWidth / 5)
                it.fling(Direction.DOWN)
                device.waitForIdle()
                it.fling(Direction.UP)
                device.waitForIdle()
            }
        }
    }

    @Test
    fun generateRecipeDetailProfile() {
        rule.collect(
            packageName = PACKAGE_NAME
        ) {
            startActivityAndWait()

            // Tap the first recipe to open detail view
            val recipeCard = device.wait(
                Until.findObject(By.clickable(true).hasDescendant(By.textContains(""))),
                TIMEOUT
            )
            recipeCard?.click()
            device.waitForIdle()

            // Scroll through recipe detail
            val detailScroll = device.findObject(By.scrollable(true))
            detailScroll?.let {
                it.setGestureMargin(device.displayWidth / 5)
                it.fling(Direction.DOWN)
                device.waitForIdle()
            }

            // Navigate back
            device.pressBack()
            device.waitForIdle()
        }
    }

    companion object {
        private const val PACKAGE_NAME = "com.lionotter.recipes"
        private const val TIMEOUT = 5_000L
    }
}
