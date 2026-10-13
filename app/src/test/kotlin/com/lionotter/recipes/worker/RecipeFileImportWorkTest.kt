package com.lionotter.recipes.worker

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.lionotter.recipes.data.image.ImageUploadPreparer
import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.ImportFileFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.RecipeErrorException
import com.lionotter.recipes.domain.model.RecipeFileFormat
import com.lionotter.recipes.domain.port.ParseAttachment
import com.lionotter.recipes.domain.usecase.ImportRecipeUseCase
import com.lionotter.recipes.domain.usecase.ParseHtmlUseCase
import com.lionotter.recipes.notification.RecipeNotificationHelper
import com.lionotter.recipes.testutil.FakeAiSettings
import com.lionotter.recipes.testutil.FakeImageStore
import com.lionotter.recipes.testutil.FakeImportDebugLog
import com.lionotter.recipes.testutil.FakePageContentExtractor
import com.lionotter.recipes.testutil.FakePageFetcher
import com.lionotter.recipes.testutil.FakeRecipeImageScheduler
import com.lionotter.recipes.testutil.FakeRecipeParser
import com.lionotter.recipes.testutil.FakeRecipeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import javax.inject.Provider

/**
 * A recipe file is copied into [ImportInputStore] when the import starts (the permission to read
 * the picked file doesn't survive process death) and the import worker reads it from there.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Photos are decoded and scaled
class RecipeFileImportWorkTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Suppress("InjectDispatcher") // The ImportInputStore only writes the test's input files
    private val inputStore = ImportInputStore(context, Dispatchers.Unconfined)

    private val parser = FakeRecipeParser()
    private val repository = FakeRecipeRepository()
    private val extractor = FakePageContentExtractor(canonicalUrl = "https://example.com/bread")
    private val imageStore = FakeImageStore()
    private val parseHtml =
        ParseHtmlUseCase(parser, extractor, repository, FakeImportDebugLog(), FakeAiSettings(), imageStore, FakeRecipeImageScheduler())

    private val runningWork = RunningAccountWork(Provider { error("Not used by this test") })

    private val factory = object : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters
        ): ListenableWorker = RecipeImportWorker(
            appContext,
            workerParameters,
            // No pages: FakePageFetcher fails the test if anything is downloaded
            ImportRecipeUseCase(FakePageFetcher(), extractor, parseHtml),
            inputStore,
            RecipeAttachmentLoader(ImageUploadPreparer(Dispatchers.Unconfined), Dispatchers.Unconfined),
            RecipeNotificationHelper(appContext),
            runningWork
        )
    }

    private fun worker(importId: String) = TestListenableWorkerBuilder<RecipeImportWorker>(context)
        .setInputData(RecipeImportWorker.createInputData(url = null, importId = importId))
        .setWorkerFactory(factory)
        .build()

    private fun pickedFile(name: String, content: String): Uri =
        Uri.fromFile(File(context.cacheDir, name).apply { writeText(content) })

    private suspend fun stage(importId: String, content: String, kind: ImportKind) {
        inputStore.copySource(importId, pickedFile("$importId.picked", content))
        inputStore.writeKind(importId, kind)
    }

    @Test
    fun `a picked file is copied and read back with its format`() = runTest {
        stage("file-1", "\uFEFF# Bread", ImportKind.TEXT_FILE)

        assertEquals(StagedRecipeFile(RecipeFileFormat.TEXT, "# Bread"), inputStore.readRecipeFile("file-1"))
    }

    @Test
    fun `a file over the limit is rejected and nothing is kept`() = runTest {
        try {
            inputStore.copySource("file-2", pickedFile("big.md", "x".repeat(101)), maxBytes = 100)
            fail("Expected the file to be rejected")
        } catch (e: RecipeErrorException) {
            assertEquals(RecipeError.ImportFile(ImportFileFailure.TOO_LARGE, 100), e.recipeError)
        }
        assertFalse(inputStore.hasSource("file-2"))
        assertFalse(File(inputStore.importDir("file-2"), "source.partial").exists())
    }

    @Test
    fun `a file at the limit is accepted`() = runTest {
        inputStore.copySource("file-3", pickedFile("exact.md", "x".repeat(100)), maxBytes = 100)
        assertEquals(100L, inputStore.sourceFile("file-3").length())
    }

    @Test
    fun `URL imports and archives are not recipe files`() = runTest {
        assertNull(inputStore.readRecipeFile("url-import"))
        assertFalse(inputStore.hasImport("url-import"))

        stage("zip-1", "PK", ImportKind.ZIP)
        assertNull(inputStore.readRecipeFile("zip-1"))
    }

    @Test
    fun `a text file is sent to the AI as is and its files are removed`() = runTest {
        stage("file-4", "# Bread\n- flour", ImportKind.TEXT_FILE)

        val result = worker("file-4").doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals("# Bread\n- flour", parser.requests.single().content)
        assertNull(repository.saved.single().sourceUrl)
        assertFalse(inputStore.hasImport("file-4"))
    }

    @Test
    fun `an HTML file goes through page extraction`() = runTest {
        stage("file-5", "<html>saved</html>", ImportKind.HTML_FILE)

        val result = worker("file-5").doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals("extracted(<html>saved</html>)", parser.requests.single().content)
        assertEquals("https://example.com/bread", repository.saved.single().sourceUrl)
    }

    @Test
    fun `a failed import keeps the file for a retry`() = runTest {
        parser.result = Outcome.Failure(RecipeError.Ai(AiFailure.INVALID_API_KEY, 401))
        stage("file-6", "# Bread", ImportKind.TEXT_FILE)

        val result = worker("file-6").doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals("# Bread", inputStore.readRecipeFile("file-6")?.content)
    }

    @Test
    fun `an import whose file is gone can't be retried`() = runTest {
        stage("file-7", "# Bread", ImportKind.TEXT_FILE)
        inputStore.sourceFile("file-7").delete()

        val result = worker("file-7").doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(
            RecipeImportWork.Outcome.Failed(RecipeError.CannotRetry, "file-7"),
            RecipeImportWork.decodeOutcome(succeeded = false, (result as ListenableWorker.Result.Failure).outputData)
        )
        assertTrue(parser.requests.isEmpty())
    }

    private fun pickedPhoto(name: String, width: Int, height: Int): Uri {
        val file = File(context.cacheDir, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return Uri.fromFile(file)
    }

    private fun dimensions(bytes: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth to options.outHeight
    }

    @Test
    fun `photos are scaled down and sent in order, and the first becomes the recipe's image`() = runTest {
        inputStore.copySource("photos-1", pickedPhoto("page1.jpg", 4000, 1000), index = 0)
        inputStore.copySource("photos-1", pickedPhoto("page2.jpg", 300, 400), index = 1)
        inputStore.writeKind("photos-1", ImportKind.IMAGE_FILE)

        val result = worker("photos-1").doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        val request = parser.requests.single()
        assertEquals("", request.content)
        val (first, second) = request.attachments.map { it as ParseAttachment.Image }
        assertEquals(RecipeAttachmentLoader.MAX_AI_IMAGE_DIMENSION to 392, dimensions(first.bytes))
        assertEquals(300 to 400, dimensions(second.bytes))
        assertEquals(listOf("image/jpeg", "image/jpeg"), request.attachments.map { it.mediaType })
        assertEquals(listOf(first.bytes.size), imageStore.savedStreams)
        val recipe = repository.saved.single()
        assertEquals("gs://bucket/stream.jpg", recipe.imageUrl)
        assertNull(recipe.sourceUrl)
        assertNull(recipe.sourceImageUrl)
        assertFalse(inputStore.hasImport("photos-1"))
    }

    @Test
    fun `a recipe photo that can't be saved leaves the recipe without an image`() = runTest {
        imageStore.streamUri = { _, _ -> throw IOException("disk full") }
        inputStore.copySource("photos-5", pickedPhoto("card.jpg", 300, 400))
        inputStore.writeKind("photos-5", ImportKind.IMAGE_FILE)

        val result = worker("photos-5").doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertNull(repository.saved.single().imageUrl)
    }

    @Test
    fun `a PDF is sent as is and gives no image`() = runTest {
        stage("pdf-1", "%PDF-1.4 recipe", ImportKind.PDF_FILE)

        val result = worker("pdf-1").doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        val pdf = parser.requests.single().attachments.single() as ParseAttachment.Pdf
        assertEquals("%PDF-1.4 recipe", String(pdf.bytes))
        assertEquals("application/pdf", pdf.mediaType)
        assertTrue(imageStore.savedStreams.isEmpty())
        assertNull(repository.saved.single().imageUrl)
    }

    @Test
    fun `a photo that isn't an image fails as unreadable and isn't sent`() = runTest {
        stage("photos-2", "not a photo", ImportKind.IMAGE_FILE)

        val result = worker("photos-2").doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(
            RecipeImportWork.Outcome.Failed(RecipeError.ImportFile(ImportFileFailure.UNREADABLE), "photos-2"),
            RecipeImportWork.decodeOutcome(succeeded = false, (result as ListenableWorker.Result.Failure).outputData)
        )
        assertTrue(parser.requests.isEmpty())
    }

    @Test
    fun `a photo import whose files are gone can't be retried`() = runTest {
        stage("photos-3", "x", ImportKind.IMAGE_FILE)
        inputStore.sourceFile("photos-3").delete()

        val result = worker("photos-3").doWork()

        assertEquals(
            RecipeImportWork.Outcome.Failed(RecipeError.CannotRetry, "photos-3"),
            RecipeImportWork.decodeOutcome(succeeded = false, (result as ListenableWorker.Result.Failure).outputData)
        )
    }

    @Test
    fun `staged photos are read back in order`() = runTest {
        inputStore.copySource("photos-4", pickedFile("a", "first"), index = 0)
        inputStore.copySource("photos-4", pickedFile("b", "second"), index = 1)
        inputStore.writeKind("photos-4", ImportKind.IMAGE_FILE)

        val staged = inputStore.readRecipeFile("photos-4")!!

        assertEquals(RecipeFileFormat.IMAGE, staged.format)
        assertNull(staged.content)
        assertEquals(listOf("first", "second"), staged.sources.map { it.readText() })
    }

    @Test
    fun `import kinds keep the values older versions stored`() {
        assertEquals(
            listOf("url", "paprika", "zip", "text_file", "html_file", "image_file", "pdf_file"),
            ImportKind.entries.map { it.value }
        )
        assertEquals(ImportKind.IMAGE_FILE, ImportKind.forRecipeFile(RecipeFileFormat.IMAGE))
        assertEquals(ImportKind.PDF_FILE, ImportKind.forRecipeFile(RecipeFileFormat.PDF))
    }
}
