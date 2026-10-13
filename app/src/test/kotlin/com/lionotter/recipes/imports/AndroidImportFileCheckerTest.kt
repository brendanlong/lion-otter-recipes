package com.lionotter.recipes.imports

import android.graphics.Bitmap
import android.graphics.Color
import com.lionotter.recipes.domain.ImportFileFailure
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.RecipeErrorException
import com.lionotter.recipes.domain.model.RecipeFileFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidImportFileCheckerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Suppress("InjectDispatcher") // Reads the test's own files
    private val checker = AndroidImportFileChecker(Dispatchers.Unconfined)

    @Test
    fun `a photo this device can decode passes`() = runTest {
        val photo = tempFolder.newFile("photo.jpg")
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }

        checker.check(photo, RecipeFileFormat.IMAGE)
    }

    @Test
    fun `a photo this device can't decode is reported at once`() = runTest {
        val photo = tempFolder.newFile("photo.heic").apply { writeText("not an image this device reads") }

        try {
            checker.check(photo, RecipeFileFormat.IMAGE)
            fail("Expected the photo to be rejected")
        } catch (e: RecipeErrorException) {
            assertEquals(RecipeError.ImportFile(ImportFileFailure.UNREADABLE), e.recipeError)
        }
    }

    @Test
    fun `text files aren't checked`() = runTest {
        checker.check(tempFolder.newFile("bread.md").apply { writeText("# Bread") }, RecipeFileFormat.TEXT)
    }
}
