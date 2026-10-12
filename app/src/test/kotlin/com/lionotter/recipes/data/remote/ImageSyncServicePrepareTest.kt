package com.lionotter.recipes.data.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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
class ImageSyncServicePrepareTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val service = ImageSyncService(RuntimeEnvironment.getApplication(), mockk(relaxed = true))

    private fun writeJpeg(width: Int, height: Int, orientation: Int? = null): File {
        val file = tempFolder.newFile("img-${width}x$height-${orientation ?: 0}.jpg")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (orientation != null) {
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
        }
        return file
    }

    private fun dimensions(bytes: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth to options.outHeight
    }

    @Test
    fun `inSampleSize keeps longest side at or above target`() {
        assertEquals(1, ImageSyncService.calculateInSampleSize(4000, 3000, 2048))
        assertEquals(2, ImageSyncService.calculateInSampleSize(4096, 3072, 2048))
        assertEquals(4, ImageSyncService.calculateInSampleSize(12000, 1000, 2048))
        assertEquals(1, ImageSyncService.calculateInSampleSize(800, 600, 2048))
    }

    @Test
    fun `small upright jpeg is uploaded unchanged`() = runBlocking {
        val file = writeJpeg(400, 300)
        val prepared = service.prepareForUpload(file)
        assertArrayEquals(file.readBytes(), prepared.bytes)
        assertEquals("image/jpeg", prepared.mimeType)
    }

    @Test
    fun `exif rotated image is rotated upright`() = runBlocking {
        val file = writeJpeg(400, 300, ExifInterface.ORIENTATION_ROTATE_90)
        val prepared = service.prepareForUpload(file)
        assertEquals(300 to 400, dimensions(prepared.bytes))
    }

    @Test
    fun `large image is scaled to max dimension`() = runBlocking {
        val file = writeJpeg(4000, 1000)
        val prepared = service.prepareForUpload(file)
        val (width, height) = dimensions(prepared.bytes)
        assertEquals(ImageSyncService.MAX_IMAGE_DIMENSION, width)
        assertEquals(512, height)
        assertTrue(prepared.bytes.size <= ImageCacheConfig.MAX_IMAGE_BYTES)
    }

    @Test(expected = IOException::class)
    fun `non-image data is rejected`(): Unit = runBlocking {
        val file = tempFolder.newFile("not-an-image.jpg").apply { writeText("<html>nope</html>") }
        service.prepareForUpload(file)
    }
}
