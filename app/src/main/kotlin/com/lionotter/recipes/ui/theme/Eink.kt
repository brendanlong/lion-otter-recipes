package com.lionotter.recipes.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.FloatingActionButtonElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The few greys the e-ink theme uses. E-ink panels draw multiples of 17 (0x11) flat and dither
 * everything else, so these are exact panel levels.
 */
object EinkGray {
    /** Secondary text, which still clears 7:1 on white. */
    val Dark = Color(0xFF555555)

    /** Chrome that shouldn't read as content, like the scrollbar. */
    val Medium = Color(0xFF888888)
}

/**
 * E-ink: every surface white and every edge and text black. Contrast comes from borders, never
 * fills, except that "on" is a black fill (selected chips and segmented buttons).
 */
internal val EinkColorScheme = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    primaryContainer = Color.White,
    onPrimaryContainer = Color.Black,
    inversePrimary = Color.White,
    secondary = Color.Black,
    onSecondary = Color.White,
    secondaryContainer = Color.Black,
    onSecondaryContainer = Color.White,
    tertiary = Color.Black,
    onTertiary = Color.White,
    tertiaryContainer = Color.White,
    onTertiaryContainer = Color.Black,
    error = Color.Black,
    onError = Color.White,
    errorContainer = Color.White,
    onErrorContainer = Color.Black,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color.White,
    onSurfaceVariant = EinkGray.Dark,
    surfaceTint = Color.White,
    inverseSurface = Color.Black,
    inverseOnSurface = Color.White,
    outline = Color.Black,
    outlineVariant = Color.Black,
    scrim = Color.Black,
    surfaceBright = Color.White,
    surfaceDim = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainerLowest = Color.White,
)

/** Whether the e-ink theme is showing, for the few things colors alone can't adjust. */
// Part of the theme, like MaterialTheme's own locals: set once by LionOtterTheme.
@Suppress("ComposeCompositionLocalUsage")
val LocalEinkTheme = staticCompositionLocalOf { false }

/**
 * The border every card needs in the e-ink theme, where it's white on white; none otherwise.
 * [width] is thicker for selected or highlighted cards.
 */
@Composable
@ReadOnlyComposable
fun einkBorder(width: Dp = 1.dp): BorderStroke? =
    if (LocalEinkTheme.current) BorderStroke(width, MaterialTheme.colorScheme.outline) else null

/**
 * In the e-ink theme, a 1dp line along the top or bottom edge, for bars that sit on the white
 * page with nothing else to set them apart; nothing otherwise.
 */
@Composable
fun Modifier.einkEdge(atTop: Boolean): Modifier {
    if (!LocalEinkTheme.current) return this
    val color = MaterialTheme.colorScheme.outline
    return drawWithContent {
        drawContent()
        val stroke = 1.dp.toPx()
        val y = if (atTop) stroke / 2 else size.height - stroke / 2
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
    }
}

/** A floating action button's usual shadow, except in the e-ink theme. */
@Composable
fun fabElevation(): FloatingActionButtonElevation =
    if (LocalEinkTheme.current) {
        FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)
    } else {
        FloatingActionButtonDefaults.elevation()
    }

/** [elevation] as a card's shadow, except in the e-ink theme, which has no shadows. */
@Composable
fun cardElevation(elevation: Dp): CardElevation =
    CardDefaults.cardElevation(defaultElevation = if (LocalEinkTheme.current) 0.dp else elevation)
