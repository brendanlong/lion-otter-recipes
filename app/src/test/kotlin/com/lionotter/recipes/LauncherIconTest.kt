package com.lionotter.recipes

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class LauncherIconTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun launcherEntries(): List<String> =
        context.packageManager
            .queryIntentActivities(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setPackage(context.packageName),
                0
            )
            .map { it.activityInfo.name }

    private fun state(alias: String) =
        context.packageManager.getComponentEnabledSetting(ComponentName(context, alias))

    private fun launchedFrom(component: String): Activity =
        Robolectric.buildActivity(
            Activity::class.java,
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(context, component))
        ).create().get()

    @Test
    fun `the manifest shows only the default icon`() {
        assertEquals(listOf(DEFAULT_ICON), launcherEntries())
    }

    @Test
    fun `the e-ink theme shows only the e-ink icon`() {
        context.useLauncherIcon(eink = true)
        assertEquals(listOf(EINK_ICON), launcherEntries())
    }

    @Test
    fun `leaving the e-ink theme puts the manifest's state back`() {
        context.useLauncherIcon(eink = true)
        context.useLauncherIcon(eink = false)
        assertEquals(listOf(DEFAULT_ICON), launcherEntries())
        assertEquals(COMPONENT_ENABLED_STATE_DEFAULT, state(DEFAULT_ICON))
        assertEquals(COMPONENT_ENABLED_STATE_DEFAULT, state(EINK_ICON))
    }

    @Test
    fun `a launch from the icon being hidden reopens from the shown one`() {
        val activity = launchedFrom(DEFAULT_ICON)
        assertTrue(activity.matchLauncherIcon(eink = true))
        assertTrue(activity.isFinishing)
        assertEquals(listOf(EINK_ICON), launcherEntries())
        val reopened = shadowOf(activity).nextStartedActivity
        assertEquals(ComponentName(context, EINK_ICON), reopened.component)
        assertEquals(Intent.ACTION_MAIN, reopened.action)
        assertTrue(reopened.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `a launch from the shown icon stays`() {
        context.useLauncherIcon(eink = true)
        val activity = launchedFrom(EINK_ICON)
        assertFalse(activity.matchLauncherIcon(eink = true))
        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `a launch not from the launcher changes nothing`() {
        val activity = launchedFrom(RecipesActivity::class.java.name)
        assertFalse(activity.matchLauncherIcon(eink = true))
        assertEquals(listOf(DEFAULT_ICON), launcherEntries())
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
