package com.lionotter.recipes

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lionotter.recipes.imports.ImportFileInspector
import com.lionotter.recipes.notification.RecipeNotificationHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Something an external intent (share, file open, notification tap) asked the app to do.
 */
sealed interface AppIntentEvent {
    data class ImportUrl(val url: String) : AppIntentEvent

    /**
     * A shared or opened file; [mimeType] is the type the sending app gave. [SharedIntentViewModel]
     * delivers it as is if it's a recipe archive, or as [ImportRecipeFile].
     */
    data class ImportFile(val uri: Uri, val mimeType: String? = null) : AppIntentEvent

    /** A Markdown, text or HTML file with one recipe, imported directly. */
    data class ImportRecipeFile(val uri: Uri, val mimeType: String? = null) : AppIntentEvent
    data class OpenRecipe(val recipeId: String) : AppIntentEvent
    /** Text was shared with the app but it didn't contain a web link. */
    data object NoUrlInSharedText : AppIntentEvent
}

/**
 * Delivers intent events from [RecipesActivity] to the navigation graph exactly once.
 *
 * Backed by a buffered [Channel] so events sent before the UI starts collecting
 * (e.g. during onCreate, or while auth is still resolving) are not dropped, and
 * each event is consumed by a single collector so it isn't replayed on rotation.
 */
@HiltViewModel
class SharedIntentViewModel @Inject constructor(
    private val importFileInspector: ImportFileInspector
) : ViewModel() {
    companion object {
        private const val TAG = "SharedIntentViewModel"
    }

    private val _events = Channel<AppIntentEvent>(Channel.BUFFERED)
    val events: Flow<AppIntentEvent> = _events.receiveAsFlow()

    fun onEvent(event: AppIntentEvent) {
        if (event is AppIntentEvent.ImportFile) {
            viewModelScope.launch { _events.send(resolveFile(event)) }
        } else {
            _events.trySend(event)
        }
    }

    /** Whether the file goes to the selection screen (an archive) or is imported directly. */
    private suspend fun resolveFile(event: AppIntentEvent.ImportFile): AppIntentEvent = try {
        importFileInspector.recipeFile(event.uri, event.mimeType)
            ?.let { AppIntentEvent.ImportRecipeFile(it.uri, event.mimeType) }
            ?: event
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The selection screen reports a file it can't open
        Log.w(TAG, "Couldn't inspect ${event.uri}", e)
        event
    }
}

/** What this intent asks the app to do, or null if nothing. */
fun Intent.toAppIntentEvent(): AppIntentEvent? {
    getStringExtra(RecipeNotificationHelper.EXTRA_RECIPE_ID)?.let {
        return AppIntentEvent.OpenRecipe(it)
    }
    return when (action) {
        Intent.ACTION_VIEW -> data?.let { AppIntentEvent.ImportFile(it, type) }
        Intent.ACTION_SEND -> {
            val text = getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            val stream = IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)
            val isTextShare = type?.startsWith("text/") == true
            val url = text?.let(::extractFirstUrl)
            when {
                stream != null && !isTextShare -> AppIntentEvent.ImportFile(stream, type)
                // A shared text, Markdown or HTML file (notes apps may send its text along), unless
                // the text is only a link
                stream != null && (url == null || text.trim() != url) -> AppIntentEvent.ImportFile(stream, type)
                url != null -> AppIntentEvent.ImportUrl(url)
                isTextShare || !text.isNullOrBlank() -> AppIntentEvent.NoUrlInSharedText
                else -> null
            }
        }
        else -> null
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
