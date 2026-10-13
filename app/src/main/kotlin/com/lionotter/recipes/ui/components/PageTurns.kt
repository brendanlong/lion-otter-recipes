package com.lionotter.recipes.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * How far a page turn scrolls, as a fraction of the screen: like a browser's Page Down, so the
 * last couple of lines stay in view to read on from.
 */
const val PAGE_FRACTION = 0.875f

/** Pixels to scroll for one page turn [direction] (1 down, -1 up) on a screen [height] tall. */
fun pageScrollDistance(height: Int, direction: Int): Float = direction * height * PAGE_FRACTION

/**
 * What the volume buttons scroll: each long screen registers while it's showing, and the latest
 * one gets the turn. A target scrolls down a page for 1 and up for -1, and says whether it could
 * (it can't before it's laid out).
 */
class PageTurns {
    private class Target(val turn: (Int) -> Boolean)

    private val targets = mutableListOf<Target>()

    /** Registers [turn]; the returned function unregisters it. */
    fun register(turn: (Int) -> Boolean): () -> Unit {
        val target = Target(turn)
        targets += target
        return { targets.remove(target) }
    }

    /** Turns the page of the latest screen; false when no screen that scrolls is showing. */
    fun turn(direction: Int): Boolean = targets.lastOrNull()?.turn?.invoke(direction) ?: false
}

/** The Activity's [PageTurns]; null where nothing listens for page turns (previews, tests). */
// Set once by RecipesActivity, which owns the key events; threading it through every screen's
// parameters would only hand it down unchanged.
@Suppress("ComposeCompositionLocalUsage")
val LocalPageTurns = staticCompositionLocalOf<PageTurns?> { null }
