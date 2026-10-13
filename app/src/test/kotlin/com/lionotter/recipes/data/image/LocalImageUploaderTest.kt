package com.lionotter.recipes.data.image

import com.lionotter.recipes.data.remote.AuthService
import com.lionotter.recipes.data.remote.AuthState
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.MealType
import com.lionotter.recipes.testutil.FakeMealPlanRepository
import com.lionotter.recipes.testutil.FakeRecipeRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class LocalImageUploaderTest {

    private val recipes = FakeRecipeRepository()
    private val mealPlans = FakeMealPlanRepository()
    private var googleUser = true
    private val authState = MutableStateFlow<AuthState>(AuthState.Google("u1", "a@b.c"))
    private val authService = mockk<AuthService> {
        every { isGoogleUser() } answers { googleUser }
        every { authState } returns this@LocalImageUploaderTest.authState
    }

    private class Env(val uploader: LocalImageUploader, val files: LocalImageFiles, val remote: FakeRemoteImageStorage)

    private fun TestScope.env(): Env {
        val io = StandardTestDispatcher(testScheduler)
        val files = LocalImageFiles(RuntimeEnvironment.getApplication(), io)
        val remote = FakeRemoteImageStorage(files)
        return Env(LocalImageUploader(files, remote, recipes, mealPlans, authService, io), files, remote)
    }

    private suspend fun Env.localImage(): String = files.save(pngBytes().inputStream(), ".png")!!

    private fun storageUrlOf(localUrl: String) = "gs://bucket/users/u1/images/" + localUrl.substringAfterLast('/')

    private fun mealPlan(id: String, recipeId: String, imageUrl: String?) = MealPlanEntry(
        id = id,
        recipeId = recipeId,
        recipeName = "Recipe",
        recipeImageUrl = imageUrl,
        date = LocalDate(2024, 1, 1),
        mealType = MealType.DINNER,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0)
    )

    @Test
    fun `local images are uploaded and recipes and meal plans point at the uploads`() = runTest {
        val env = env()
        val local = env.localImage()
        recipes.saveRecipe(imageRecipe("r1", local))
        recipes.saveRecipe(imageRecipe("r2", "gs://bucket/users/u1/images/already.jpg"))
        recipes.saveRecipe(imageRecipe("r3", null))
        mealPlans.saveMealPlan(mealPlan("m1", "r1", local))

        val result = env.uploader.uploadPendingLocalImages()

        assertEquals(LocalImageUploader.Result(uploaded = 1, failed = 0), result)
        assertEquals(storageUrlOf(local), recipes.recipes.value.getValue("r1").imageUrl)
        assertEquals(storageUrlOf(local), mealPlans.entries.value.getValue("m1").recipeImageUrl)
        assertEquals("gs://bucket/users/u1/images/already.jpg", recipes.recipes.value.getValue("r2").imageUrl)
        assertFalse(env.uploader.hasPendingLocalImages())
    }

    @Test
    fun `a failed upload keeps the local image and is reported for a retry`() = runTest {
        val env = env()
        env.remote.failUploads = true
        val local = env.localImage()
        recipes.saveRecipe(imageRecipe("r1", local))

        val result = env.uploader.uploadPendingLocalImages()

        assertEquals(LocalImageUploader.Result(uploaded = 0, failed = 1), result)
        assertEquals(local, recipes.recipes.value.getValue("r1").imageUrl)
        assertTrue(File(local.removePrefix("file://")).exists())
        assertTrue(env.uploader.hasPendingLocalImages())

        env.remote.failUploads = false
        assertEquals(LocalImageUploader.Result(uploaded = 1, failed = 0), env.uploader.uploadPendingLocalImages())
    }

    @Test
    fun `an image changed during the upload is not reverted, and the upload is deleted`() = runTest {
        val env = env()
        val local = env.localImage()
        recipes.saveRecipe(imageRecipe("r1", local))
        recipes.beforeReplaceImageUrl = { id -> recipes.setImageUrl(id, "file:///newer.jpg") }

        val result = env.uploader.uploadPendingLocalImages()

        assertEquals(LocalImageUploader.Result(uploaded = 0, failed = 0), result)
        assertEquals("file:///newer.jpg", recipes.recipes.value.getValue("r1").imageUrl)
        assertEquals(listOf("upload:${local.substringAfterLast('/')}", "delete:${storageUrlOf(local)}"), env.remote.events)
    }

    @Test
    fun `failing to update the recipe counts as a failure`() = runTest {
        val env = env()
        val local = env.localImage()
        recipes.saveRecipe(imageRecipe("r1", local))
        recipes.beforeReplaceImageUrl = { throw IOException("offline") }

        assertEquals(LocalImageUploader.Result(uploaded = 0, failed = 1), env.uploader.uploadPendingLocalImages())
    }

    @Test
    fun `guests upload nothing and have nothing pending`() = runTest {
        googleUser = false
        authState.value = AuthState.Guest("g1")
        val env = env()
        recipes.saveRecipe(imageRecipe("r1", env.localImage()))

        assertEquals(LocalImageUploader.Result(uploaded = 0, failed = 0), env.uploader.uploadPendingLocalImages())
        assertTrue(env.remote.events.isEmpty())
        assertFalse(env.uploader.hasPendingLocalImages())
    }

    @Test
    fun `an upload pass started during sign-in waits for it to finish`() = runTest {
        authState.value = AuthState.Loading
        val env = env()
        recipes.saveRecipe(imageRecipe("r1", env.localImage()))

        val pass = async { env.uploader.uploadPendingLocalImages() }
        advanceUntilIdle()
        assertFalse(pass.isCompleted)
        authState.value = AuthState.Google("u1", "a@b.c")

        assertEquals(LocalImageUploader.Result(uploaded = 1, failed = 0), pass.await())
    }

    @Test
    fun `images pending upload are assumed when recipes can't be read`() = runTest {
        val env = env()
        recipes.imageQueryError = IOException("cache unavailable")

        assertTrue(env.uploader.hasPendingLocalImages())
    }
}
