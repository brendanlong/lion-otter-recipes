package com.lionotter.recipes.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Benchmarks app startup with and without Baseline Profiles to measure improvement.
 *
 * Run on a physical device:
 * ```
 * ./gradlew :baselineprofile:connectedBenchmarkAndroidTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun startupWithoutCompilation() {
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.None(),
            iterations = 5,
            startupMode = StartupMode.COLD
        ) {
            startActivityAndWait()
        }
    }

    @Test
    fun startupWithBaselineProfile() {
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.Partial(
                baselineProfileMode = BaselineProfileMode.Require
            ),
            iterations = 5,
            startupMode = StartupMode.COLD
        ) {
            startActivityAndWait()
        }
    }

    companion object {
        private const val PACKAGE_NAME = "com.lionotter.recipes"
    }
}
