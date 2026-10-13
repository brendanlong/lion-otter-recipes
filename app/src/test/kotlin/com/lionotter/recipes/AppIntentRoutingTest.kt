package com.lionotter.recipes

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.lionotter.recipes.domain.model.RecipeFileFormat
import com.lionotter.recipes.imports.ContentResolverImportFileInspector
import com.lionotter.recipes.imports.ImportFileInspector
import com.lionotter.recipes.imports.RecipeFile
import com.lionotter.recipes.notification.RecipeNotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Which import (or other action) a share, file open or notification tap leads to. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AppIntentRoutingTest {

    private val fileUri = Uri.parse("content://files/document/42")

    private fun send(type: String, text: String? = null, stream: Uri? = null) = Intent(Intent.ACTION_SEND).apply {
        this.type = type
        text?.let { putExtra(Intent.EXTRA_TEXT, it) }
        stream?.let { putExtra(Intent.EXTRA_STREAM, it) }
    }

    @Test
    fun `shared text with a link imports the link`() {
        assertEquals(
            AppIntentEvent.ImportUrl("https://example.com/bread"),
            send("text/plain", text = "Banana bread https://example.com/bread").toAppIntentEvent()
        )
    }

    @Test
    fun `shared text without a link is reported`() {
        assertEquals(AppIntentEvent.NoUrlInSharedText, send("text/plain", text = "just some words").toAppIntentEvent())
        assertEquals(AppIntentEvent.NoUrlInSharedText, send("text/plain").toAppIntentEvent())
    }

    @Test
    fun `a shared text file is imported as a file`() {
        assertEquals(
            AppIntentEvent.ImportFile(fileUri, "text/plain"),
            send("text/plain", stream = fileUri).toAppIntentEvent()
        )
        assertEquals(
            AppIntentEvent.ImportFile(fileUri, "text/markdown"),
            send("text/markdown", stream = fileUri, text = "Bread.md").toAppIntentEvent()
        )
    }

    @Test
    fun `a shared link wins over a file shared with it`() {
        assertEquals(
            AppIntentEvent.ImportUrl("https://example.com/bread"),
            send("text/plain", text = "https://example.com/bread", stream = fileUri).toAppIntentEvent()
        )
    }

    @Test
    fun `a shared note file wins over a link in its text`() {
        assertEquals(
            AppIntentEvent.ImportFile(fileUri, "text/markdown"),
            send("text/markdown", text = "# Bread\nFrom https://example.com/bread", stream = fileUri).toAppIntentEvent()
        )
    }

    @Test
    fun `a shared archive is imported as a file even with text`() {
        assertEquals(
            AppIntentEvent.ImportFile(fileUri, "application/zip"),
            send("application/zip", text = "https://drive.example.com/file", stream = fileUri).toAppIntentEvent()
        )
    }

    @Test
    fun `an opened file is imported as a file`() {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(fileUri, "text/html")
        assertEquals(AppIntentEvent.ImportFile(fileUri, "text/html"), intent.toAppIntentEvent())
    }

    @Test
    fun `a shared photo or PDF is imported as a file`() {
        assertEquals(AppIntentEvent.ImportFile(fileUri, "image/jpeg"), send("image/jpeg", stream = fileUri).toAppIntentEvent())
        assertEquals(
            AppIntentEvent.ImportFile(fileUri, "application/pdf"),
            send("application/pdf", stream = fileUri, text = "Bread.pdf").toAppIntentEvent()
        )
    }

    @Test
    fun `a link shared with a preview picture imports the link`() {
        assertEquals(
            AppIntentEvent.ImportUrl("https://example.com/bread"),
            send("image/jpeg", text = "Banana bread https://example.com/bread", stream = fileUri).toAppIntentEvent()
        )
    }

    @Test
    fun `an opened photo or PDF is imported as a file`() {
        val pdf = Intent(Intent.ACTION_VIEW).setDataAndType(fileUri, "application/pdf")
        assertEquals(AppIntentEvent.ImportFile(fileUri, "application/pdf"), pdf.toAppIntentEvent())
        val photo = Intent(Intent.ACTION_VIEW).setDataAndType(fileUri, "image/png")
        assertEquals(AppIntentEvent.ImportFile(fileUri, "image/png"), photo.toAppIntentEvent())
    }

    private fun sendMultiple(type: String, vararg streams: Uri) = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        this.type = type
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(*streams))
    }

    @Test
    fun `several shared photos are imported as one recipe, in order`() {
        val second = Uri.parse("content://files/document/43")
        assertEquals(
            AppIntentEvent.ImportRecipeFiles(listOf(fileUri, second), "image/*"),
            sendMultiple("image/*", fileUri, second).toAppIntentEvent()
        )
    }

    @Test
    fun `a single photo shared as several is imported as a file`() {
        assertEquals(AppIntentEvent.ImportFile(fileUri, "image/jpeg"), sendMultiple("image/jpeg", fileUri).toAppIntentEvent())
        assertNull(sendMultiple("image/jpeg").toAppIntentEvent())
    }

    @Test
    fun `a notification tap opens its recipe`() {
        val intent = Intent(Intent.ACTION_MAIN).putExtra(RecipeNotificationHelper.EXTRA_RECIPE_ID, "r1")
        assertEquals(AppIntentEvent.OpenRecipe("r1"), intent.toAppIntentEvent())
    }

    @Test
    fun `other intents do nothing`() {
        assertNull(Intent(Intent.ACTION_MAIN).toAppIntentEvent())
        assertNull(Intent(Intent.ACTION_SEND).apply { type = "image/png" }.toAppIntentEvent())
    }

    @Before
    fun setMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun viewModel(inspector: ImportFileInspector) = SharedIntentViewModel(inspector)

    @Test
    fun `a recipe file is imported directly`() = runTest {
        val viewModel = viewModel { uri, mimeType ->
            assertEquals("text/markdown", mimeType)
            RecipeFile(uri, "Bread.md", RecipeFileFormat.TEXT)
        }

        viewModel.onEvent(AppIntentEvent.ImportFile(fileUri, "text/markdown"))

        assertEquals(AppIntentEvent.ImportRecipeFiles(listOf(fileUri), "text/markdown"), viewModel.events.first())
    }

    @Test
    fun `an archive goes to the selection screen`() = runTest {
        val viewModel = viewModel { _, _ -> null }

        viewModel.onEvent(AppIntentEvent.ImportFile(fileUri, "application/zip"))

        assertEquals(AppIntentEvent.ImportFile(fileUri, "application/zip"), viewModel.events.first())
    }

    @Test
    fun `a file that can't be inspected goes to the selection screen, which reports it`() = runTest {
        val viewModel = viewModel { _, _ -> throw SecurityException("no permission") }

        viewModel.onEvent(AppIntentEvent.ImportFile(fileUri, null))

        assertEquals(AppIntentEvent.ImportFile(fileUri, null), viewModel.events.first())
    }

    @Test
    fun `other events are delivered as they are`() = runTest {
        val viewModel = viewModel { _, _ -> error("not a file") }

        viewModel.onEvent(AppIntentEvent.ImportUrl("https://example.com/bread"))

        assertEquals(AppIntentEvent.ImportUrl("https://example.com/bread"), viewModel.events.first())
    }

    @Test
    fun `the content resolver inspector recognizes recipe files by name`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        @Suppress("InjectDispatcher") // Reads the test's own files
        val inspector = ContentResolverImportFileInspector(context, Dispatchers.Unconfined)
        val markdown = Uri.fromFile(File(context.cacheDir, "Bread.md").apply { writeText("# Bread") })
        val export = Uri.fromFile(File(context.cacheDir, "export.lorecipes").apply { writeText("PK") })

        val photo = Uri.fromFile(File(context.cacheDir, "IMG_1.HEIC").apply { writeText("heic") })
        val pdf = Uri.fromFile(File(context.cacheDir, "Bread.pdf").apply { writeText("%PDF") })

        assertEquals(RecipeFile(markdown, "Bread.md", RecipeFileFormat.TEXT), inspector.recipeFile(markdown, null))
        assertNull(inspector.recipeFile(export, "application/zip"))
        assertEquals(RecipeFileFormat.IMAGE, inspector.recipeFile(photo, null)?.format)
        assertEquals(RecipeFileFormat.PDF, inspector.recipeFile(pdf, null)?.format)
    }
}
