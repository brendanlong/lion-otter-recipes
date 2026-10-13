package com.lionotter.recipes.data.image

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalImageFilesTest {

    private fun TestScope.files() = LocalImageFiles(RuntimeEnvironment.getApplication(), StandardTestDispatcher(testScheduler))

    private fun fileOf(localUrl: String) = File(localUrl.removePrefix("file://"))

    @Test
    fun `saved images are new files in the image directory`() = runTest {
        val files = files()
        val bytes = pngBytes()

        val url = files.save(bytes.inputStream(), ".png")!!

        assertEquals(LocalImageFiles.IMAGE_DIR, fileOf(url).parentFile?.name)
        assertTrue(url.endsWith(".png"))
        assertArrayEquals(bytes, fileOf(url).readBytes())
        assertEquals(fileOf(url), files.file(url))
    }

    @Test
    fun `data that isn't an image is rejected and leaves no file behind`() = runTest {
        val files = files()

        assertNull(files.save("<html>nope</html>".byteInputStream(), ".jpg"))
        assertNull(files.save(ByteArray(0).inputStream(), ".jpg"))

        assertEquals(emptyList<String>(), files.dir.list()!!.toList())
    }

    @Test
    fun `images over the size limit are rejected`() = runTest {
        val files = files()
        val huge = object : InputStream() {
            var remaining = LocalImageFiles.MAX_LOCAL_IMAGE_BYTES + 1
            override fun read(): Int = if (remaining-- > 0) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val n = minOf(len.toLong(), remaining).toInt()
                remaining -= n
                return n
            }
        }

        assertNull(files.save(huge, ".jpg"))
        assertEquals(emptyList<String>(), files.dir.list()!!.toList())
    }

    @Test
    fun `a failed atomic write leaves the target untouched`() = runTest {
        val files = files()
        val target = File(files.dir, "target.jpg").apply { writeText("old") }

        val result = runCatching { files.writeAtomically(target) { temp -> temp.writeText("partial"); throw IOException("cut off") } }

        assertTrue(result.isFailure)
        assertEquals("old", target.readText())
        assertEquals(listOf("target.jpg"), files.dir.list()!!.toList())
    }

    @Test
    fun `copies are independent files`() = runTest {
        val files = files()
        val original = files.save(pngBytes().inputStream(), ".png")!!

        val copy = files.copy(original)!!
        files.delete(original)

        assertNotEquals(original, copy)
        assertFalse(fileOf(original).exists())
        assertTrue(fileOf(copy).exists())
    }

    @Test
    fun `only files in the image directory are deleted`() = runTest {
        val files = files()
        val outside = File(RuntimeEnvironment.getApplication().filesDir, "keep.jpg").apply { writeText("x") }

        files.delete("file://${outside.absolutePath}")

        assertTrue(outside.exists())
        assertNull(files.file("file://${outside.absolutePath}"))
    }

    @Test
    fun `cached copies are named after the stored image and cleared with everything else`() = runTest {
        val files = files()
        val local = files.save(pngBytes().inputStream(), ".png")!!
        val cached = files.cacheFileFor("gs://bucket/users/u1/images/a.jpg").apply { writeText("x") }
        assertEquals("a.jpg", cached.name)

        files.clearUserData()

        assertFalse(cached.exists())
        assertFalse(fileOf(local).exists())
    }
}
