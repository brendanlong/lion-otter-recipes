package com.lionotter.recipes.ui.components

import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.flow.filter
import kotlin.math.roundToInt

/**
 * Anchor points for the swipe gesture.
 */
enum class SwipeActionAnchor {
    /** Resting position (not swiped). */
    Settled,
    /** Peeked from start to end (left-to-right in LTR), revealing background. */
    PeekStartToEnd,
    /** Peeked from end to start (right-to-left in LTR), revealing background. */
    PeekEndToStart,
    /** Fully dismissed toward start-to-end direction. */
    DismissedStartToEnd,
    /** Fully dismissed toward end-to-start direction. */
    DismissedEndToStart
}

/**
 * The current swipe direction, used by background content to decide which icon to show.
 */
enum class SwipeActionDirection {
    /** Not swiped. */
    Settled,
    /** Swiping from start to end (left-to-right in LTR). */
    StartToEnd,
    /** Swiping from end to start (right-to-left in LTR). */
    EndToStart
}

private const val PEEK_FRACTION = 0.25f

/**
 * State for a [SwipeActionBox]. Callers use this to respond to swipe actions
 * by either confirming (slide off-screen) or resetting (snap back).
 */
@Stable
class SwipeActionBoxState internal constructor(
    internal val anchoredState: AnchoredDraggableState<SwipeActionAnchor>
) {
    /** The direction the card is currently peeked at, or null if settled/animating. */
    val peekDirection: SwipeActionDirection
        get() = when (anchoredState.settledValue) {
            SwipeActionAnchor.PeekStartToEnd -> SwipeActionDirection.StartToEnd
            SwipeActionAnchor.PeekEndToStart -> SwipeActionDirection.EndToStart
            else -> SwipeActionDirection.Settled
        }

    /**
     * Confirm the swipe action: animate the card fully off-screen in the peeked direction.
     */
    suspend fun confirm() {
        when (anchoredState.settledValue) {
            SwipeActionAnchor.PeekStartToEnd ->
                anchoredState.animateTo(SwipeActionAnchor.DismissedStartToEnd, tween())
            SwipeActionAnchor.PeekEndToStart ->
                anchoredState.animateTo(SwipeActionAnchor.DismissedEndToStart, tween())
            else -> {}
        }
    }

    /**
     * Reset the card back to its resting position.
     */
    suspend fun reset() {
        anchoredState.animateTo(SwipeActionAnchor.Settled)
    }
}

/**
 * Create and remember a [SwipeActionBoxState].
 */
@Composable
fun rememberSwipeActionBoxState(): SwipeActionBoxState {
    val anchoredState = rememberSaveable(saver = AnchoredDraggableState.Saver()) {
        AnchoredDraggableState(initialValue = SwipeActionAnchor.Settled)
    }
    return remember { SwipeActionBoxState(anchoredState) }
}

/**
 * A swipe-action container that uses [AnchoredDraggableState] instead of the deprecated
 * `SwipeToDismissBox` with `confirmValueChange`.
 *
 * When the user swipes past the threshold, the card peeks to ~25% revealing the background
 * action icon, and the [onStartToEndAction] or [onEndToStartAction] callback fires.
 * The card stays at the peek position until the caller resolves the action via
 * [SwipeActionBoxState.confirm] (slide off-screen) or [SwipeActionBoxState.reset] (snap back).
 *
 * @param state The [SwipeActionBoxState] controlling this component.
 * @param modifier Modifier for the outer container.
 * @param onStartToEndAction Called when a left-to-right swipe (in LTR) peeks past threshold.
 * @param onEndToStartAction Called when a right-to-left swipe (in LTR) peeks past threshold.
 * @param enableStartToEnd Whether to allow swiping from start to end.
 * @param enableEndToStart Whether to allow swiping from end to start.
 * @param backgroundContent Content drawn behind the main content, typically showing action icons.
 *   Receives the current [SwipeActionDirection] to decide which icon to display.
 * @param content The foreground content that the user swipes.
 */
@Composable
fun SwipeActionBox(
    modifier: Modifier = Modifier,
    state: SwipeActionBoxState = rememberSwipeActionBoxState(),
    onStartToEndAction: () -> Unit = {},
    onEndToStartAction: () -> Unit = {},
    enableStartToEnd: Boolean = false,
    enableEndToStart: Boolean = true,
    backgroundContent: @Composable BoxScope.(SwipeActionDirection) -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val anchoredState = state.anchoredState

    // Track the measured width so we can add dismissed anchors dynamically.
    var measuredWidth by remember { mutableFloatStateOf(0f) }

    // Capture the latest callbacks without restarting the effect (which would
    // break snapshotFlow's deduplication and cause missed events).
    val currentOnStartToEnd by rememberUpdatedState(onStartToEndAction)
    val currentOnEndToStart by rememberUpdatedState(onEndToStartAction)

    // When the state settles at a peek position, fire the action callback.
    // The card stays peeked until the caller calls confirm() or reset().
    LaunchedEffect(anchoredState) {
        snapshotFlow { anchoredState.settledValue }
            .filter {
                it == SwipeActionAnchor.PeekStartToEnd ||
                    it == SwipeActionAnchor.PeekEndToStart
            }
            .collect { settled ->
                when (settled) {
                    SwipeActionAnchor.PeekStartToEnd -> currentOnStartToEnd()
                    SwipeActionAnchor.PeekEndToStart -> currentOnEndToStart()
                    else -> {}
                }
            }
    }

    // After a dismissed animation completes, reset back to settled so the item
    // is ready for reuse (e.g. in a LazyColumn that hasn't removed it yet).
    LaunchedEffect(anchoredState) {
        snapshotFlow { anchoredState.settledValue }
            .filter {
                it == SwipeActionAnchor.DismissedStartToEnd ||
                    it == SwipeActionAnchor.DismissedEndToStart
            }
            .collect {
                anchoredState.animateTo(SwipeActionAnchor.Settled)
            }
    }

    // Derive the current swipe direction for the background content.
    val currentDirection by remember(isRtl) {
        derivedStateOf {
            val offset = anchoredState.offset
            when {
                !offset.isNaN() && offset > 0f ->
                    if (isRtl) SwipeActionDirection.EndToStart
                    else SwipeActionDirection.StartToEnd
                !offset.isNaN() && offset < 0f ->
                    if (isRtl) SwipeActionDirection.StartToEnd
                    else SwipeActionDirection.EndToStart
                else -> SwipeActionDirection.Settled
            }
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        backgroundContent(currentDirection)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { size ->
                    measuredWidth = size.width.toFloat()
                }
                .offset {
                    IntOffset(
                        x = if (!anchoredState.offset.isNaN()) {
                            anchoredState.offset.roundToInt()
                        } else {
                            0
                        },
                        y = 0
                    )
                }
                .anchoredDraggable(
                    state = anchoredState,
                    orientation = Orientation.Horizontal,
                    reverseDirection = isRtl
                ),
            content = content
        )
    }

    // Dynamically add/remove dismissed anchors based on the current settled value.
    // When the caller calls confirm(), the state targets a dismissed anchor that
    // needs to exist; after reset, we remove it so the user can't drag there freely.
    LaunchedEffect(anchoredState, measuredWidth, enableStartToEnd, enableEndToStart, isRtl) {
        snapshotFlow { anchoredState.settledValue }
            .collect { settled ->
                if (measuredWidth <= 0f) return@collect
                val peekDistance = measuredWidth * PEEK_FRACTION
                anchoredState.updateAnchors(
                    buildAnchors(
                        isRtl = isRtl,
                        enableStartToEnd = enableStartToEnd,
                        enableEndToStart = enableEndToStart,
                        peekDistance = peekDistance,
                        fullDistance = measuredWidth,
                        includeDismissed = settled.isPeeked()
                    )
                )
            }
    }
}

private fun SwipeActionAnchor.isPeeked(): Boolean =
    this == SwipeActionAnchor.PeekStartToEnd || this == SwipeActionAnchor.PeekEndToStart


private fun buildAnchors(
    isRtl: Boolean,
    enableStartToEnd: Boolean,
    enableEndToStart: Boolean,
    peekDistance: Float,
    fullDistance: Float,
    includeDismissed: Boolean
): DraggableAnchors<SwipeActionAnchor> = DraggableAnchors {
    SwipeActionAnchor.Settled at 0f
    if (enableStartToEnd) {
        val sign = if (isRtl) -1f else 1f
        SwipeActionAnchor.PeekStartToEnd at peekDistance * sign
        if (includeDismissed) {
            SwipeActionAnchor.DismissedStartToEnd at fullDistance * sign
        }
    }
    if (enableEndToStart) {
        val sign = if (isRtl) 1f else -1f
        SwipeActionAnchor.PeekEndToStart at peekDistance * sign
        if (includeDismissed) {
            SwipeActionAnchor.DismissedEndToStart at fullDistance * sign
        }
    }
}
