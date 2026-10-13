package com.lionotter.recipes

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

// Named from the namespace, which the debug build's application ID suffix doesn't change.
internal const val DEFAULT_ICON = "com.lionotter.recipes.DefaultIcon"
internal const val EINK_ICON = "com.lionotter.recipes.EinkIcon"
private val ICONS = listOf(DEFAULT_ICON, EINK_ICON)

private fun shownIcon(eink: Boolean): String = if (eink) EINK_ICON else DEFAULT_ICON

/**
 * Shows the black-and-white launcher icon with the e-ink theme and the color one otherwise.
 * Android has no resource qualifier for this, so the manifest has a launcher entry (an
 * activity-alias of MainActivity) per icon, and this enables one. Without the e-ink theme both
 * are left as the manifest has them, so nothing changes for anyone who never picks it. Swapping
 * drops shortcuts pinned to the old entry.
 */
fun Context.useLauncherIcon(eink: Boolean) {
    val shown = shownIcon(eink)
    // Enable the new entry first, so there's never a moment with neither.
    for (alias in listOf(shown) + (ICONS - shown)) {
        val component = ComponentName(this, alias)
        val state = when {
            !eink -> PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            alias == shown -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        if (packageManager.getComponentEnabledSetting(component) != state) {
            packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        }
    }
}

/**
 * For an activity started from one of the launcher entries: shows the icon for [eink]
 * ([useLauncherIcon]) and, if this launch came from the entry that hides, reopens from the shown
 * one. Android removes a task started from a launcher entry once that entry is disabled (even
 * with DONT_KILL_APP), so that launch would otherwise close moments after it opens. Returns
 * whether it reopened, in which case this activity is finishing.
 */
fun Activity.matchLauncherIcon(eink: Boolean): Boolean {
    val launchedFrom = intent.component?.className
    if (launchedFrom !in ICONS) return false
    useLauncherIcon(eink)
    val shown = shownIcon(eink)
    if (launchedFrom == shown) return false
    finishAndRemoveTask()
    startActivity(
        Intent(intent)
            .setComponent(ComponentName(this, shown))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    return true
}
