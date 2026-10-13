package com.lionotter.recipes.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.lionotter.recipes.messages.UserMessage
import com.lionotter.recipes.messages.resolve
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** The localized text of this message. */
@Composable
@ReadOnlyComposable
fun UserMessage.text(): String = resolve(LocalResources.current)

/**
 * Handles one-off [events] (a ViewModel's `Channel.receiveAsFlow()`) while the screen is at
 * least STARTED. Events sent while it is stopped stay in the channel and are handled on return.
 */
@Composable
fun <T> CollectEvents(events: Flow<T>, onEvent: suspend (T) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnEvent by rememberUpdatedState(onEvent)
    LaunchedEffect(events, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Main.immediate so an event is never received and then dropped between dispatches.
            withContext(Dispatchers.Main.immediate) {
                events.collect { currentOnEvent(it) }
            }
        }
    }
}
