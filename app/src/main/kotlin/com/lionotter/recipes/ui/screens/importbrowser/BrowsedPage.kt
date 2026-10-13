package com.lionotter.recipes.ui.screens.importbrowser

import com.lionotter.recipes.util.ErrorLog
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * The page the user chose in the in-app browser: the address it ended up at, and its HTML as
 * rendered, saved with [com.lionotter.recipes.worker.ImportInputStore.writeWorkText] (the
 * receiving screen hands the file to the import). [htmlPath] is null when the user chose to
 * import without the browser, so the import downloads [url] itself.
 *
 * Passed back through a [com.lionotter.recipes.ui.navigation.NavResult], so it is small and
 * [java.io.Serializable].
 */
data class BrowsedPage(val url: String, val htmlPath: String?) : java.io.Serializable {
    private companion object {
        private const val serialVersionUID = 1L
    }
}

internal fun String.isWebUrl(): Boolean =
    startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

private const val TAG = "BrowsedPage"

/**
 * Decodes a [android.webkit.WebView.evaluateJavascript] result. The callback gets the script's
 * value as JSON, so a string arrives quoted with its characters escaped (`"<html>` is
 * `"<html>`), and anything that isn't a string (e.g. `null`) is not page text.
 */
fun decodeJavascriptString(result: String?): String? {
    if (result.isNullOrEmpty()) return null
    return try {
        (Json.parseToJsonElement(result) as? JsonPrimitive)?.takeIf { it.isString }?.content
    } catch (e: SerializationException) {
        ErrorLog.w(TAG, "evaluateJavascript returned something that isn't JSON", e)
        null
    }
}
