package com.lionotter.recipes

import android.net.Uri
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject

/**
 * Something an external intent (share, file open, notification tap) asked the app to do.
 */
sealed interface AppIntentEvent {
    data class ImportUrl(val url: String) : AppIntentEvent
    data class ImportFile(val uri: Uri) : AppIntentEvent
    data class OpenRecipe(val recipeId: String) : AppIntentEvent
    /** Text was shared with the app but it didn't contain a web link. */
    data object NoUrlInSharedText : AppIntentEvent
}

/**
 * Delivers intent events from [MainActivity] to the navigation graph exactly once.
 *
 * Backed by a buffered [Channel] so events sent before the UI starts collecting
 * (e.g. during onCreate, or while auth is still resolving) are not dropped, and
 * each event is consumed by a single collector so it isn't replayed on rotation.
 */
@HiltViewModel
class SharedIntentViewModel @Inject constructor() : ViewModel() {
    private val _events = Channel<AppIntentEvent>(Channel.BUFFERED)
    val events: Flow<AppIntentEvent> = _events.receiveAsFlow()

    fun onEvent(event: AppIntentEvent) {
        _events.trySend(event)
    }
}

private val URL_REGEX = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)
private const val TRAILING_URL_PUNCTUATION = ".,;:!?)]}'\""

/**
 * Finds the first http(s) URL in shared text. Apps commonly share
 * "Title https://example.com/recipe" or "Title\nhttps://..." rather than a bare URL.
 */
fun extractFirstUrl(text: String): String? {
    val match = URL_REGEX.find(text) ?: return null
    return match.value.trimEnd { it in TRAILING_URL_PUNCTUATION }.takeIf { it.substringAfter("://").isNotEmpty() }
}
