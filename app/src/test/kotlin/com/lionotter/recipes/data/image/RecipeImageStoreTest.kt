package com.lionotter.recipes.data.image

import com.lionotter.recipes.data.remote.AuthService
import com.lionotter.recipes.domain.port.ImportedImage
import com.lionotter.recipes.testutil.FakeRecipeRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeImageStoreTest {

    private val requests = mutableListOf<String>()
    private val httpClient = HttpClient(MockEngine) {
        engine {
            addHandler { request ->
                val url = request.url.toString()
                requests += url
                when {
                    url.startsWith("http://") -> respond("", HttpStatusCode.Forbidden)
                    url.endsWith(".html") -> respond(
                        "<html></html>",
                        headers = headersOf(HttpHeaders.ContentType, "text/html")
                    )
                    else -> respond(pngBytes(), headers = headersOf(HttpHeaders.ContentType, "image/png"))
                }
            }
        }
    }
    private val recipes = FakeRecipeRepository()
    private var googleUser = false
    private val authService = mockk<AuthService> { every { isGoogleUser() } answers { googleUser } }
    private var scheduledUploads = 0

    private class Env(val store: RecipeImageStore, val files: LocalImageFiles, val remote: FakeRemoteImageStorage)

    private fun TestScope.env(): Env {
        val io = StandardTestDispatcher(testScheduler)
        val files = LocalImageFiles(RuntimeEnvironment.getApplication(), io)
        val remote = FakeRemoteImageStorage(files)
        val store = RecipeImageStore(
            files, ImageDownloader(httpClient, files), remote, recipes, authService, { scheduledUploads++ }, this
        )
        return Env(store, files, remote)
    }

    private suspend fun Env.localImage(): String = files.save(pngBytes().inputStream(), ".png")!!

    private fun fileOf(localUrl: String) = File(localUrl.removePrefix("file://"))

    // --- importFromUrl ---

    @Test
    fun `web image is downloaded, retrying http over https`() = runTest {
        val env = env()

        val image = env.store.importFromUrl("http://example.com/pie.png")!!

        assertEquals(listOf("http://example.com/pie.png", "https://example.com/pie.png"), requests)
        assertEquals("https://example.com/pie.png", image.sourceUrl)
        assertTrue(fileOf(image.uri).exists())
    }

    @Test
    fun `a page that isn't an image is rejected`() = runTest {
        assertNull(env().store.importFromUrl("https://example.com/error.html"))
    }

    @Test
    fun `stored images are used as is, missing local ones are unavailable`() = runTest {
        val env = env()
        val local = env.localImage()

        assertEquals(ImportedImage("gs://b/x.jpg", "gs://b/x.jpg"), env.store.importFromUrl("gs://b/x.jpg"))
        assertEquals(ImportedImage(local, local), env.store.importFromUrl(local))
        assertNull(env.store.importFromUrl("file:///elsewhere/gone.jpg"))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `content URLs from imported data are not read`() = runTest {
        assertNull(env().store.importFromUrl("content://com.other.app/secret.jpg"))
        assertNull(env().store.importFromUrl("ftp://example.com/x.jpg"))
        assertTrue(requests.isEmpty())
    }

    // --- attach ---

    @Test
    fun `guest images stay local and the replaced image is deleted`() = runTest {
        val env = env()
        val old = env.localImage()
        val new = env.localImage()
        recipes.saveRecipe(imageRecipe("r1", old))

        val stored = env.store.attach(new, recipeId = "r1", previousImageUrl = old)

        assertEquals(new, stored)
        assertFalse(fileOf(old).exists())
        assertTrue(fileOf(new).exists())
        assertTrue(env.remote.events.isEmpty())
    }

    @Test
    fun `the replaced image is deleted only after the new one is uploaded`() = runTest {
        googleUser = true
        val env = env()
        val new = env.localImage()
        env.remote.objects["gs://bucket/users/u1/images/old.jpg"] = byteArrayOf(1)
        recipes.saveRecipe(imageRecipe("r1", "gs://bucket/users/u1/images/old.jpg"))

        val stored = env.store.attach(new, recipeId = "r1", previousImageUrl = "gs://bucket/users/u1/images/old.jpg")

        val name = fileOf(new).name
        assertEquals("gs://bucket/users/u1/images/$name", stored)
        assertEquals(listOf("upload:$name", "delete:gs://bucket/users/u1/images/old.jpg"), env.remote.events)
        // The local file doubles as the cached copy of the upload
        assertTrue(fileOf(new).exists())
    }

    @Test
    fun `a failed upload keeps the local file and the previous image, and schedules a retry`() = runTest {
        googleUser = true
        val env = env()
        env.remote.failUploads = true
        val old = "gs://bucket/users/u1/images/old.jpg"
        env.remote.objects[old] = byteArrayOf(1)
        recipes.saveRecipe(imageRecipe("r1", old))
        val new = env.localImage()

        val stored = env.store.attach(new, recipeId = "r1", previousImageUrl = old)

        assertEquals(new, stored)
        assertTrue(fileOf(new).exists())
        assertTrue(old in env.remote.objects)
        assertEquals(1, scheduledUploads)
    }

    @Test
    fun `an image shared with a copy is kept when the original's image is replaced`() = runTest {
        val env = env()
        val shared = env.localImage()
        recipes.saveRecipe(imageRecipe("original", shared))
        recipes.saveRecipe(imageRecipe("copy", shared))

        env.store.attach(env.localImage(), recipeId = "original", previousImageUrl = shared)

        assertTrue(fileOf(shared).exists())
    }

    // --- release ---

    @Test
    fun `releasing an unused stored image deletes it and its cached copy`() = runTest {
        val env = env()
        val url = "gs://bucket/users/u1/images/a.jpg"
        env.remote.objects[url] = pngBytes()
        val cached = env.store.localImageFile(url)!!
        recipes.saveRecipe(imageRecipe("r1", url))
        recipes.deleteRecipe("r1")

        env.store.release(url, "r1")
        advanceUntilIdle()

        assertEquals(listOf("delete:$url"), env.remote.events)
        assertFalse(cached.exists())
    }

    @Test
    fun `releasing an image another recipe uses keeps it`() = runTest {
        val env = env()
        val url = "gs://bucket/users/u1/images/a.jpg"
        env.remote.objects[url] = pngBytes()
        recipes.saveRecipe(imageRecipe("r1", url))
        recipes.saveRecipe(imageRecipe("r2", url))

        env.store.release(url, "r1")
        advanceUntilIdle()

        assertTrue(env.remote.events.isEmpty())
        assertTrue(url in env.remote.objects)
    }

    @Test
    fun `an image is kept if it can't be told whether it is in use`() = runTest {
        val env = env()
        val local = env.localImage()
        recipes.imageQueryError = IOException("offline")

        env.store.release(local, "r1")
        advanceUntilIdle()

        assertTrue(fileOf(local).exists())
    }

    @Test
    fun `releasing a local image deletes the file`() = runTest {
        val env = env()
        val local = env.localImage()

        env.store.release(local, "r1")
        advanceUntilIdle()

        assertFalse(fileOf(local).exists())
    }

    // --- export ---

    @Test
    fun `local image files for export`() = runTest {
        val env = env()
        val local = env.localImage()

        assertEquals(fileOf(local), env.store.localImageFile(local))
        assertNotNull(env.store.localImageFile(local))
        assertNull(env.store.localImageFile("gs://bucket/missing.jpg"))
        assertNull(env.store.localImageFile("https://example.com/web.jpg"))
    }
}
