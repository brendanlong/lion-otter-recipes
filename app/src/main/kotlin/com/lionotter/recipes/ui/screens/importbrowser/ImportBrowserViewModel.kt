package com.lionotter.recipes.ui.screens.importbrowser

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.ResourceLimits
import com.lionotter.recipes.messages.UserMessage
import com.lionotter.recipes.ui.navigation.Route
import com.lionotter.recipes.util.DefaultDispatcher
import com.lionotter.recipes.util.ErrorLog
import com.lionotter.recipes.util.IoDispatcher
import com.lionotter.recipes.worker.ImportInputStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Why the browser can't show the page. */
enum class BrowserLoadError {
    /** The page didn't load (no connection, unknown host, timeout, …). */
    NETWORK,

    /** The page's certificate is invalid, so it wasn't loaded. */
    SSL,

    /** The WebView's renderer stopped; a new WebView is needed. */
    CRASHED,

    /** The device has no working WebView. */
    UNAVAILABLE
}

/** What the WebView reports while it shows a page. */
interface BrowserPageListener {
    fun onPageStarted(url: String)
    fun onPageFinished(url: String, title: String?, canGoBack: Boolean)

    /** The URL changed without a page load (e.g. history.pushState), or the history did. */
    fun onHistoryChanged(url: String, canGoBack: Boolean)
    fun onProgressChanged(progress: Int)
    fun onTitleChanged(title: String?)
    fun onLoadFailed(error: BrowserLoadError)

    /** The WebView is about to load [url] as the page (not a resource on it). */
    fun onNavigationRequested(url: String)

    /** A new WebView replaced the previous one (e.g. after the activity was recreated). */
    fun onWebViewCreated()

    /** The certificate of [url] (the page or something on it) is invalid; the load was cancelled. */
    fun onSslError(url: String?)
    fun onExternalLinkFailed()
}

data class BrowserUiState(
    val url: String,
    val title: String? = null,
    val progress: Int = 0,
    val isLoading: Boolean = true,
    val canGoBack: Boolean = false,
    val loadError: BrowserLoadError? = null,
    val isCapturing: Boolean = false
)

sealed interface ImportBrowserEvent {
    /** The user chose this page; hand it to the screen that opened the browser. */
    data class PageChosen(val page: BrowsedPage) : ImportBrowserEvent

    data class ShowMessage(val message: UserMessage) : ImportBrowserEvent
}

/**
 * State of the in-app browser used to confirm a page before importing it. The WebView reports
 * what it is doing to [pageListener]; "Import" captures the rendered page
 * ([onPageCaptured]) and saves it to a file so it can go to the import without a download
 * (which many recipe sites block for anything but a browser).
 */
@HiltViewModel
class ImportBrowserViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val inputStore: ImportInputStore,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @param:DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val route = savedStateHandle.toRoute<Route.ImportBrowser>()

    /** Whether the page re-imports an existing recipe (changes the action's label). */
    val reimport: Boolean = route.reimport

    /** Where a new WebView starts: the page last shown (after recreation) or the requested one. */
    val startUrl: String get() = savedStateHandle[KEY_CURRENT_URL] ?: route.url

    private val _uiState = MutableStateFlow(BrowserUiState(url = startUrl))
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    private val _events = Channel<ImportBrowserEvent>(Channel.BUFFERED)
    val events: Flow<ImportBrowserEvent> = _events.receiveAsFlow()

    /** Set once a page was handed back, so a second tap can't send another one. */
    private var pageChosen = false

    /** The page the WebView was last asked to load; it may never start if it fails early (e.g. SSL). */
    private var requestedUrl: String? = null

    fun onRetry() {
        _uiState.update { it.copy(loadError = null, isLoading = true, progress = 0) }
    }

    /** Handed to the WebView, which reports what it is doing here. */
    val pageListener: BrowserPageListener = PageListener()

    private inner class PageListener : BrowserPageListener {
        override fun onPageStarted(url: String) {
            rememberUrl(url)
            _uiState.update {
                it.copy(url = url, title = it.title.takeIf { _ -> url == it.url }, isLoading = true, loadError = null)
            }
        }

        override fun onPageFinished(url: String, title: String?, canGoBack: Boolean) {
            rememberUrl(url)
            _uiState.update {
                it.copy(url = url, title = title?.takeIf(String::isNotBlank) ?: it.title, isLoading = false, canGoBack = canGoBack)
            }
        }

        override fun onHistoryChanged(url: String, canGoBack: Boolean) {
            rememberUrl(url)
            _uiState.update { it.copy(url = url, canGoBack = canGoBack) }
        }

        override fun onProgressChanged(progress: Int) {
            _uiState.update { it.copy(progress = progress) }
        }

        override fun onTitleChanged(title: String?) {
            if (!title.isNullOrBlank()) _uiState.update { it.copy(title = title) }
        }

        override fun onLoadFailed(error: BrowserLoadError) {
            _uiState.update { it.copy(loadError = error, isLoading = false, isCapturing = false) }
        }

        override fun onNavigationRequested(url: String) {
            requestedUrl = url
        }

        override fun onWebViewCreated() {
            // A capture running in the previous WebView never completes
            if (!pageChosen) _uiState.update { it.copy(isCapturing = false) }
        }

        override fun onSslError(url: String?) {
            // Only the page itself failing is an error; a broken image or script just doesn't load
            if (url != null && (url.sameUrl(_uiState.value.url) || requestedUrl?.let { url.sameUrl(it) } == true)) {
                onLoadFailed(BrowserLoadError.SSL)
            }
        }

        override fun onExternalLinkFailed() {
            _events.trySend(ImportBrowserEvent.ShowMessage(UserMessage(R.string.no_app_to_open_link)))
        }
    }

    /** Returns false if a capture is already running, so the page is only captured once. */
    fun beginCapture(): Boolean {
        val state = _uiState.value
        if (pageChosen || state.isCapturing || state.loadError != null) return false
        _uiState.update { it.copy(isCapturing = true) }
        return true
    }

    /**
     * The page's HTML arrived from `evaluateJavascript` ([scriptResult], still JSON-encoded) while
     * the WebView showed [pageUrl].
     */
    fun onPageCaptured(scriptResult: String?, pageUrl: String?) {
        viewModelScope.launch {
            val html = withContext(defaultDispatcher) {
                decodeJavascriptString(scriptResult)?.take(ResourceLimits.MAX_PAGE_HTML_CHARS)
            }
            if (html.isNullOrBlank()) {
                ErrorLog.w(TAG, "The page's HTML couldn't be read")
                captureFailed()
                return@launch
            }
            val url = listOfNotNull(pageUrl, _uiState.value.url).firstOrNull { it.isWebUrl() } ?: route.url
            val htmlPath = try {
                withContext(NonCancellable + ioDispatcher) { inputStore.writeWorkText(html) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Failed to save the captured page", e)
                captureFailed()
                return@launch
            }
            // Stays capturing (Import disabled) while the screen closes
            pageChosen = true
            _events.send(ImportBrowserEvent.PageChosen(BrowsedPage(url, htmlPath)))
        }
    }

    /** The fallback when the page can't be shown: the import downloads the page itself. */
    fun importWithoutBrowser() {
        if (pageChosen) return
        pageChosen = true
        _events.trySend(ImportBrowserEvent.PageChosen(BrowsedPage(route.url, htmlPath = null)))
    }

    private suspend fun captureFailed() {
        _uiState.update { it.copy(isCapturing = false) }
        _events.send(ImportBrowserEvent.ShowMessage(UserMessage(R.string.browser_capture_failed)))
    }

    private fun String.sameUrl(other: String): Boolean = trimEnd('/') == other.trimEnd('/')

    private fun rememberUrl(url: String) {
        if (url.isWebUrl()) savedStateHandle[KEY_CURRENT_URL] = url
    }

    private companion object {
        private const val TAG = "ImportBrowserViewModel"
        private const val KEY_CURRENT_URL = "browser_current_url"
    }
}
