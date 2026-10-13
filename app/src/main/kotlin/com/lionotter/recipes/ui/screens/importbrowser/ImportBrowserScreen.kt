package com.lionotter.recipes.ui.screens.importbrowser

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.util.Log
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lionotter.recipes.R
import com.lionotter.recipes.messages.resolve
import com.lionotter.recipes.ui.components.CollectEvents
import com.lionotter.recipes.util.ErrorLog

private const val TAG = "ImportBrowserScreen"

private const val CAPTURE_PAGE_SCRIPT = "document.documentElement.outerHTML"

/** Non-web links the browser hands to other apps (when tapped); every other scheme is blocked. */
private val EXTERNAL_SCHEMES = setOf("mailto", "tel", "sms", "geo", "market")

/**
 * Shows a recipe page in a WebView so the user can see it (and get past cookie banners or bot
 * checks) before importing; "Import this recipe" captures the page as rendered.
 */
@Composable
fun ImportBrowserScreen(
    onBackClick: () -> Unit,
    onPageChosen: (BrowsedPage) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ImportBrowserViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var webView by remember { mutableStateOf<WebView?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current

    CollectEvents(viewModel.events) { event ->
        when (event) {
            is ImportBrowserEvent.PageChosen -> onPageChosen(event.page)
            is ImportBrowserEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message.resolve(resources))
        }
    }

    // Back goes through the page history before leaving the browser
    BackHandler(enabled = state.canGoBack && state.loadError == null) {
        webView?.goBack()
    }

    val webViewUsable = state.loadError != BrowserLoadError.CRASHED && state.loadError != BrowserLoadError.UNAVAILABLE
    // Without a usable WebView, clearing the error composes a new one, which loads the page
    val reload = {
        val hadError = state.loadError != null
        viewModel.onRetry()
        if (webViewUsable) {
            val view = webView
            if (hadError && state.url.isWebUrl()) {
                viewModel.pageListener.onNavigationRequested(state.url)
                view?.loadUrl(state.url)
            } else {
                view?.reload()
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            BrowserTopBar(
                title = state.title,
                url = state.url,
                onCloseClick = onBackClick,
                onReloadClick = reload
            )
        },
        bottomBar = {
            ImportBar(
                reimport = viewModel.reimport,
                isCapturing = state.isCapturing,
                enabled = state.loadError == null && webView != null,
                onImportClick = {
                    val view = webView
                    if (view != null && viewModel.beginCapture()) {
                        view.evaluateJavascript(CAPTURE_PAGE_SCRIPT) { result ->
                            viewModel.onPageCaptured(result, view.url)
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (webViewUsable) {
                BrowserWebView(
                    startUrl = viewModel.startUrl,
                    listener = viewModel.pageListener,
                    onWebViewChanged = { webView = it },
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (state.isLoading && state.loadError == null) {
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            state.loadError?.let { error ->
                LoadErrorContent(
                    error = error,
                    onRetryClick = reload,
                    onImportWithoutBrowserClick = viewModel::importWithoutBrowser
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserTopBar(
    title: String?,
    url: String,
    onCloseClick: () -> Unit,
    onReloadClick: () -> Unit
) {
    val host = remember(url) { url.toUri().host ?: url }
    TopAppBar(
        title = {
            Column {
                Text(
                    text = title ?: host,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = host,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onCloseClick) {
                Icon(imageVector = Icons.Default.Close, contentDescription = stringResource(R.string.browser_close))
            }
        },
        actions = {
            IconButton(onClick = onReloadClick) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = stringResource(R.string.browser_reload))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}

@Composable
private fun ImportBar(
    reimport: Boolean,
    isCapturing: Boolean,
    enabled: Boolean,
    onImportClick: () -> Unit
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.browser_import_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onImportClick,
                enabled = enabled && !isCapturing,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isCapturing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        stringResource(if (reimport) R.string.browser_reimport_this_recipe else R.string.browser_import_this_recipe)
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadErrorContent(
    error: BrowserLoadError,
    onRetryClick: () -> Unit,
    onImportWithoutBrowserClick: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Text(
                text = stringResource(
                    when (error) {
                        BrowserLoadError.NETWORK -> R.string.browser_error_network
                        BrowserLoadError.SSL -> R.string.browser_error_ssl
                        BrowserLoadError.CRASHED -> R.string.browser_error_crashed
                        BrowserLoadError.UNAVAILABLE -> R.string.browser_error_unavailable
                    }
                ),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Button(onClick = onRetryClick) {
                Text(stringResource(R.string.browser_try_again))
            }
            OutlinedButton(onClick = onImportWithoutBrowserClick) {
                Text(stringResource(R.string.browser_import_without_browser))
            }
            Text(
                text = stringResource(R.string.browser_import_without_browser_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun BrowserWebView(
    startUrl: String,
    listener: BrowserPageListener,
    onWebViewChanged: (WebView?) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnWebViewChanged by rememberUpdatedState(onWebViewChanged)
    var webView by remember { mutableStateOf<WebView?>(null) }

    // Pause scripts and media while the screen isn't shown
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, webView) {
        val view = webView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        factory = { context ->
            val view = createWebView(context, listener)
            if (view is WebView) {
                listener.onWebViewCreated()
                if (startUrl.isWebUrl()) {
                    listener.onNavigationRequested(startUrl)
                    view.loadUrl(startUrl)
                } else {
                    listener.onLoadFailed(BrowserLoadError.NETWORK)
                }
                webView = view
                currentOnWebViewChanged(view)
            }
            view
        },
        onRelease = { view ->
            if (view is WebView) {
                webView = null
                currentOnWebViewChanged(null)
                view.stopLoading()
                view.destroy()
            }
        },
        modifier = modifier
    )
}

/** A locked-down WebView, or a placeholder (with the error reported) if the device has none. */
private fun createWebView(context: Context, listener: BrowserPageListener): View {
    val webView = try {
        WebView(context)
    } catch (e: Exception) {
        // No WebView provider installed, or it is being updated
        ErrorLog.e(TAG, "Couldn't create a WebView", e)
        listener.onLoadFailed(BrowserLoadError.UNAVAILABLE)
        return View(context)
    }
    webView.settings.configure()
    webView.webViewClient = ImportWebViewClient(listener)
    webView.webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            listener.onProgressChanged(newProgress)
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            listener.onTitleChanged(title)
        }
    }
    return webView
}

// Most recipe sites need JavaScript to render (and to pass bot checks). The page can't reach
// the app: no JavaScript interface is added and file/content access is off.
@SuppressLint("SetJavaScriptEnabled")
private fun WebSettings.configure() {
    javaScriptEnabled = true
    domStorageEnabled = true
    allowFileAccess = false
    allowContentAccess = false
    javaScriptCanOpenWindowsAutomatically = false
    setSupportMultipleWindows(false)
    setGeolocationEnabled(false)
    mediaPlaybackRequiresUserGesture = true
    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
}

private class ImportWebViewClient(private val listener: BrowserPageListener) : WebViewClient() {

    /** Web links stay in the WebView; a few other schemes open in another app when tapped. */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            if (request.isForMainFrame) listener.onNavigationRequested(uri.toString())
            return false
        }
        if (scheme in EXTERNAL_SCHEMES && request.hasGesture()) {
            openExternally(view.context, uri)
        } else {
            Log.i(TAG, "Blocked navigation to a $scheme: link")
        }
        return true
    }

    private fun openExternally(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No app to open a ${uri.scheme}: link", e)
            listener.onExternalLinkFailed()
        }
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        listener.onPageStarted(url)
    }

    override fun onPageFinished(view: WebView, url: String) {
        listener.onPageFinished(url, view.title, view.canGoBack())
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        listener.onHistoryChanged(url, view.canGoBack())
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        // Failed images, ads and scripts don't matter; only the page itself does. HTTP errors
        // (e.g. a 403 bot check) still show a page the user can deal with, so they aren't errors here.
        if (request.isForMainFrame) {
            Log.w(TAG, "Page failed to load: ${error.errorCode} ${error.description}")
            listener.onLoadFailed(BrowserLoadError.NETWORK)
        }
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        Log.w(TAG, "Certificate error ${error.primaryError} for ${error.url}")
        listener.onSslError(error.url)
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        // Handled so the app survives; the screen drops this WebView and offers to try again.
        Log.w(TAG, "WebView renderer gone (crashed: ${detail.didCrash()})")
        listener.onLoadFailed(BrowserLoadError.CRASHED)
        return true
    }
}
