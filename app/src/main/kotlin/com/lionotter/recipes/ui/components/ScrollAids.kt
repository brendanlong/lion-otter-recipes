package com.lionotter.recipes.ui.components

import android.view.ViewConfiguration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.lionotter.recipes.ui.theme.EinkGray
import com.lionotter.recipes.ui.theme.LocalEinkTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

private const val THUMB_ALPHA = 0.4f
private val THUMB_WIDTH = 4.dp
private val EDGE_GAP = 2.dp
private val MIN_THUMB_LENGTH = 32.dp

/**
 * A scrollbar for a long screen, and its page turns with the volume buttons ([PageTurns]). Compose
 * has no scrollbar of its own on phones. The bar is drawn at the end edge, shown while the screen
 * scrolls and faded out after on Android's own timing; in the e-ink theme it stays shown, so the
 * screen doesn't redraw to hide it after every page turn. Make one with [rememberScrollAids] and
 * put [scrollAids] on the scrolling container, before its scroll modifier.
 */
@Stable
class ScrollAids internal constructor(
    private val state: ScrollableState,
    private val scope: CoroutineScope
) {
    internal var height = 0
    internal val alpha = Animatable(0f)
    internal var color by mutableStateOf(Color.Transparent)

    /** Scrolls a page down (1) or up (-1); false when there's nothing to scroll. */
    fun turnPage(direction: Int): Boolean {
        val canScroll = if (direction > 0) state.canScrollForward else state.canScrollBackward
        if (height == 0 || !canScroll) return false
        scope.launch { state.animateScrollBy(pageScrollDistance(height, direction)) }
        return true
    }

    internal fun ContentDrawScope.drawScrollbar() {
        val shown = alpha.value
        val indicator = state.scrollIndicatorState ?: return
        if (shown == 0f) return
        val thumb = thumbSpan(
            offset = indicator.scrollOffset,
            content = indicator.contentSize,
            viewport = indicator.viewportSize,
            track = size.height,
            minLength = MIN_THUMB_LENGTH.toPx()
        ) ?: return
        val width = THUMB_WIDTH.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width - width - EDGE_GAP.toPx(), thumb.start),
            size = Size(width, thumb.length),
            cornerRadius = CornerRadius(width / 2),
            alpha = shown
        )
    }
}

@Composable
fun rememberScrollAids(state: ScrollableState): ScrollAids {
    val scope = rememberCoroutineScope()
    val aids = remember(state, scope) { ScrollAids(state, scope) }
    val pageTurns = LocalPageTurns.current
    DisposableEffect(aids, pageTurns) {
        val unregister = pageTurns?.register(aids::turnPage)
        onDispose { unregister?.invoke() }
    }

    val eink = LocalEinkTheme.current
    val color = if (eink) EinkGray.Medium else MaterialTheme.colorScheme.onSurface.copy(alpha = THUMB_ALPHA)
    SideEffect { aids.color = color }
    LaunchedEffect(aids, eink) {
        if (eink) {
            aids.alpha.snapTo(1f)
            return@LaunchedEffect
        }
        aids.alpha.snapTo(0f)
        snapshotFlow { state.scrollIndicatorState?.scrollOffset }
            // Not for where it starts.
            .drop(1)
            .collectLatest {
                aids.alpha.snapTo(1f)
                delay(ViewConfiguration.getScrollDefaultDelay().toLong())
                aids.alpha.animateTo(0f, tween(ViewConfiguration.getScrollBarFadeDuration()))
            }
    }
    return aids
}

fun Modifier.scrollAids(aids: ScrollAids): Modifier =
    onSizeChanged { aids.height = it.height }
        .drawWithContent {
            drawContent()
            with(aids) { drawScrollbar() }
        }

internal data class ThumbSpan(val start: Float, val length: Float)

/**
 * Where a scrollbar's thumb goes along a [track] of pixels; null with nothing to scroll. A thumb
 * held at [minLength] still spans the track, top to bottom.
 */
internal fun thumbSpan(offset: Int, content: Int, viewport: Int, track: Float, minLength: Float): ThumbSpan? {
    if (viewport <= 0 || content <= viewport || track <= 0f) return null
    val length = maxOf(track * viewport / content, minLength).coerceAtMost(track)
    val start = (track - length) * offset.coerceIn(0, content - viewport) / (content - viewport)
    return ThumbSpan(start, length)
}
