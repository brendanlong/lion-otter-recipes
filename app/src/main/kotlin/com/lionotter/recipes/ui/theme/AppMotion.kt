package com.lionotter.recipes.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.ui.MotionDurationScale

/**
 * How fast every Compose animation in the app runs: the system's animation scale, or none at all
 * with [enabled] off (the e-ink theme, where animations smear). Given to the window's recomposer
 * (MainActivity), which otherwise reads the system's scale itself; with no animations each one
 * jumps straight to its end, so screen transitions, swipes and scrolls by a page are instant.
 */
class AppMotion(private val context: Context) : MotionDurationScale {
    var enabled = true
    private var systemScale = readSystemScale()

    override val scaleFactor: Float
        get() = if (enabled) systemScale else 0f

    /** Rereads the system's scale, which can change while the app is in the background. */
    fun refresh() {
        systemScale = readSystemScale()
    }

    private fun readSystemScale(): Float =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
}
