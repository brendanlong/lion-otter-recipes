package com.lionotter.recipes.ui.screens.importbrowser

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.testing.invoke
import androidx.test.core.app.ApplicationProvider
import com.lionotter.recipes.R
import com.lionotter.recipes.messages.UserMessage
import com.lionotter.recipes.ui.navigation.Route
import com.lionotter.recipes.worker.ImportInputStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ImportBrowserViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val inputStore = ImportInputStore(context, testDispatcher)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        handle: SavedStateHandle = SavedStateHandle(route = Route.ImportBrowser("https://example.com/r"))
    ) = ImportBrowserViewModel(handle, inputStore, testDispatcher, testDispatcher)

    private suspend fun ImportBrowserViewModel.chosenPage(): BrowsedPage =
        (events.first() as ImportBrowserEvent.PageChosen).page

    @Test
    fun `the captured page is saved and handed back with the final URL`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        viewModel.pageListener.onPageStarted("https://www.example.com/r/")
        viewModel.pageListener.onPageFinished("https://www.example.com/r/", "Pasta", canGoBack = false)

        assertTrue(viewModel.beginCapture())
        viewModel.onPageCaptured("\"\\u003Chtml>pasta\\u003C/html>\"", "https://www.example.com/r/")
        advanceUntilIdle()

        val page = viewModel.chosenPage()
        assertEquals("https://www.example.com/r/", page.url)
        assertEquals("<html>pasta</html>", inputStore.readWorkText(page.htmlPath!!))
        // Import stays disabled while the screen closes
        assertTrue(viewModel.uiState.value.isCapturing)
    }

    @Test
    fun `the page is captured once even if Import is tapped twice`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        assertTrue(viewModel.beginCapture())
        assertFalse(viewModel.beginCapture())
    }

    @Test
    fun `a page that can't be read shows a message and allows another try`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        viewModel.beginCapture()

        viewModel.onPageCaptured("null", "https://example.com/r")
        advanceUntilIdle()

        assertEquals(
            ImportBrowserEvent.ShowMessage(UserMessage(R.string.browser_capture_failed)),
            viewModel.events.first()
        )
        assertTrue(viewModel.beginCapture())
    }

    @Test
    fun `a non-web page URL falls back to the last web URL`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        viewModel.pageListener.onPageStarted("https://example.com/r2")
        viewModel.beginCapture()

        viewModel.onPageCaptured("\"\\u003Chtml>\\u003C/html>\"", "about:blank")
        advanceUntilIdle()

        assertEquals("https://example.com/r2", viewModel.chosenPage().url)
    }

    @Test
    fun `importing without the browser hands back the requested URL without a page`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        viewModel.pageListener.onPageStarted("https://example.com/elsewhere")
        viewModel.pageListener.onLoadFailed(BrowserLoadError.NETWORK)

        viewModel.importWithoutBrowser()

        assertEquals(BrowsedPage("https://example.com/r", htmlPath = null), viewModel.chosenPage())
    }

    @Test
    fun `load errors block capturing until retried`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        viewModel.pageListener.onLoadFailed(BrowserLoadError.NETWORK)
        assertEquals(BrowserLoadError.NETWORK, viewModel.uiState.value.loadError)
        assertFalse(viewModel.beginCapture())

        viewModel.onRetry()
        viewModel.pageListener.onPageStarted("https://example.com/r")

        assertNull(viewModel.uiState.value.loadError)
        assertTrue(viewModel.beginCapture())
    }

    @Test
    fun `a recreated browser starts at the page last shown`() = runTest(testDispatcher) {
        val handle = SavedStateHandle(route = Route.ImportBrowser("https://example.com/r", reimport = true))
        viewModel(handle).pageListener.onHistoryChanged("https://example.com/r?page=2", canGoBack = true)

        val recreated = viewModel(handle)

        assertEquals("https://example.com/r?page=2", recreated.startUrl)
        assertTrue(recreated.reimport)
    }

    @Test
    fun `a page is handed back only once`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        viewModel.beginCapture()
        viewModel.onPageCaptured("\"\\u003Chtml>\\u003C/html>\"", "https://example.com/r")
        advanceUntilIdle()

        assertFalse(viewModel.beginCapture())
        viewModel.importWithoutBrowser()

        assertTrue(viewModel.events.first() is ImportBrowserEvent.PageChosen)
        assertNull(withTimeoutOrNull(100) { viewModel.events.first() })
    }

    @Test
    fun `a capture lost with its WebView doesn't keep Import disabled`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        viewModel.beginCapture()

        viewModel.pageListener.onWebViewCreated()

        assertFalse(viewModel.uiState.value.isCapturing)
        assertTrue(viewModel.beginCapture())
    }

    @Test
    fun `a certificate error on the requested page is an error, on a resource it isn't`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        viewModel.pageListener.onPageFinished("https://example.com/r", "R", canGoBack = false)

        viewModel.pageListener.onSslError("https://ads.example.net/pixel.gif")
        assertNull(viewModel.uiState.value.loadError)

        viewModel.pageListener.onNavigationRequested("https://bad-cert.example.org")
        viewModel.pageListener.onSslError("https://bad-cert.example.org/")
        assertEquals(BrowserLoadError.SSL, viewModel.uiState.value.loadError)
    }
}
