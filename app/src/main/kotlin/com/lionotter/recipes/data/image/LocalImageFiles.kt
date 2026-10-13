package com.lionotter.recipes.data.image

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.lionotter.recipes.data.local.LocalDataScope
import com.lionotter.recipes.data.local.PerUserLocalData
import com.lionotter.recipes.util.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The on-device image directory: recipe images that only exist locally (`file://` URLs) and
 * cached copies of images in cloud storage (named after the stored object). Files appear
 * atomically (written to a temporary file, then renamed), so a partly written image is never
 * read. Nothing here touches the network.
 *
 * All functions are main-safe.
 */
@Singleton
class LocalImageFiles @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : PerUserLocalData {
    override val scope = LocalDataScope.ACCOUNT

    companion object {
        private const val TAG = "LocalImageFiles"
        const val IMAGE_DIR = "recipe_images"

        /** Maximum size of an image saved locally (before resizing for upload). */
        const val MAX_LOCAL_IMAGE_BYTES = 25 * 1024 * 1024L

        private const val FILE_SCHEME = "file://"

        fun isLocalUrl(url: String?): Boolean = url != null && url.startsWith(FILE_SCHEME)
    }

    /** The image directory, created if needed. Call on the IO dispatcher. */
    val dir: File
        get() = File(context.filesDir, IMAGE_DIR).also { if (!it.exists()) it.mkdirs() }

    private fun pathOf(localUrl: String) = File(localUrl.removePrefix(FILE_SCHEME))

    private fun urlOf(file: File) = "$FILE_SCHEME${file.absolutePath}"

    /** True if [localUrl] is a `file://` URL whose file exists. */
    suspend fun exists(localUrl: String): Boolean =
        isLocalUrl(localUrl) && withContext(ioDispatcher) { pathOf(localUrl).exists() }

    /** The file of a `file://` URL in the image directory, or null if it isn't one or doesn't exist. */
    suspend fun file(localUrl: String): File? {
        if (!isLocalUrl(localUrl)) return null
        return withContext(ioDispatcher) { pathOf(localUrl).takeIf { it.exists() && it.isInImageDir() } }
    }

    private fun File.isInImageDir() = parentFile?.name == IMAGE_DIR

    /**
     * Saves an image from [input] (closed by this function) as a new file. Returns its
     * `file://` URL, or null if the data isn't a decodable image, is larger than
     * [MAX_LOCAL_IMAGE_BYTES], or can't be written.
     *
     * @param extension File extension including the dot (e.g. ".jpg")
     */
    suspend fun save(input: InputStream, extension: String): String? = try {
        input.use { writeValidatedImage(it, extension) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Failed to save image", e)
        null
    }

    /** Saves the image at a `content://` [contentUri] (e.g. from the photo picker). Null if that fails. */
    suspend fun saveFromContentUri(contentUri: Uri): String? = try {
        val saved = withContext(ioDispatcher) {
            val extension = when (context.contentResolver.getType(contentUri)) {
                "image/png" -> ".png"
                "image/webp" -> ".webp"
                "image/gif" -> ".gif"
                "image/heic", "image/heif" -> ".heic"
                else -> ".jpg"
            }
            context.contentResolver.openInputStream(contentUri)?.let { save(it, extension) }
        }
        if (saved == null) Log.w(TAG, "Could not save image from $contentUri")
        saved
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Failed to save image from content URI: $contentUri", e)
        null
    }

    /** A new file with the content of [localUrl]. Null if it isn't a local image or can't be copied. */
    suspend fun copy(localUrl: String): String? {
        if (!isLocalUrl(localUrl)) return null
        val source = pathOf(localUrl)
        return try {
            withContext(ioDispatcher) {
                source.takeIf { it.exists() }?.inputStream()?.let { save(it, ".${source.extension.ifEmpty { "jpg" }}") }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy local image", e)
            null
        }
    }

    /** Deletes the image file of [localUrl]; files outside the image directory are never touched. */
    suspend fun delete(localUrl: String) {
        if (!isLocalUrl(localUrl)) return
        withContext(ioDispatcher) {
            val file = pathOf(localUrl)
            if (file.exists() && file.isInImageDir() && !file.delete()) {
                Log.w(TAG, "Failed to delete image: $localUrl")
            }
        }
    }

    /** Where the cached copy of the stored image [storageUrl] lives (it may not exist yet). */
    fun cacheFileFor(storageUrl: String): File = File(dir, storageUrl.substringAfterLast("/"))

    /** Deletes the cached copy of the stored image [storageUrl], if any. */
    suspend fun deleteCachedCopy(storageUrl: String) = withContext(ioDispatcher) {
        val file = cacheFileFor(storageUrl)
        if (file.exists() && !file.delete()) Log.w(TAG, "Failed to delete cached image: ${file.path}")
    }

    /**
     * Writes [target] atomically: [write] fills a temporary file in the image directory, which
     * then replaces [target] if [write] returned true. Returns whether [target] was written.
     */
    suspend fun writeAtomically(target: File, write: suspend (File) -> Boolean): Boolean = withContext(ioDispatcher) {
        val temp = File(dir, ".${UUID.randomUUID()}.tmp")
        try {
            if (!write(temp)) return@withContext false
            if (!temp.renameTo(target)) throw IOException("Failed to move image into place: ${target.name}")
            true
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /**
     * Deletes every file in the image directory (local-only images and cached copies).
     * @throws IOException if some files could not be deleted
     */
    override suspend fun clearUserData(): Unit = withContext(ioDispatcher) {
        val files = dir.listFiles() ?: return@withContext
        val failed = files.count { !it.deleteRecursively() }
        Log.d(TAG, "Cleared ${files.size - failed} files from the local image directory")
        if (failed > 0) throw IOException("Failed to delete $failed files from the local image directory")
    }

    /**
     * Copies [input] into a new file, enforcing [MAX_LOCAL_IMAGE_BYTES] and checking that the
     * result decodes as an image. Returns the `file://` URL, or null if it isn't an image.
     */
    private suspend fun writeValidatedImage(input: InputStream, extension: String): String? {
        val target = File(dir, "${UUID.randomUUID()}$extension")
        val written = writeAtomically(target) { temp ->
            temp.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_LOCAL_IMAGE_BYTES) throw IOException("Image exceeds $MAX_LOCAL_IMAGE_BYTES bytes")
                    output.write(buffer, 0, read)
                }
            }
            when {
                temp.length() == 0L -> {
                    Log.w(TAG, "Image file is empty after saving")
                    false
                }
                !isDecodableImage(temp) -> {
                    Log.w(TAG, "Saved data is not a decodable image")
                    false
                }
                else -> true
            }
        }
        return if (written) urlOf(target) else null
    }

    private fun isDecodableImage(file: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }
}
