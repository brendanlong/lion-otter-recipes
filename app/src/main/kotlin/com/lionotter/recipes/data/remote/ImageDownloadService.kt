package com.lionotter.recipes.data.remote

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import android.util.Base64
import java.io.File
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads recipe images and stores them locally in the app's internal storage.
 * Returns a file:// URI that can be stored in the recipe's imageUrl field.
 */
@Singleton
class ImageDownloadService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: HttpClient
) {
    /**
     * Result of downloading an image, containing both the local file URI
     * and the URL that was actually used to download (which may differ from
     * the original if an HTTP URL was upgraded to HTTPS).
     */
    data class DownloadResult(
        val localUri: String,
        val effectiveUrl: String
    )

    companion object {
        private const val TAG = "ImageDownloadService"
        private const val IMAGE_DIR = "recipe_images"
    }

    private fun getImageDir(): File {
        val dir = File(context.filesDir, IMAGE_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Downloads a remote image URL to local storage if needed.
     * Returns the local file URI, or null if the URL is null, invalid, or download fails.
     *
     * Handles three cases:
     * - null URL: returns null
     * - Local file:// URI: checks if the file exists (may not if imported from another device),
     *   returns the URI if it exists or null if not
     * - Remote URL: downloads and stores locally via [downloadAndStore]
     */
    suspend fun downloadImageIfNeeded(imageUrl: String?): String? {
        if (imageUrl == null) return null
        if (imageUrl.startsWith("file://")) {
            val path = imageUrl.removePrefix("file://")
            return if (File(path).exists()) imageUrl else null
        }
        return downloadAndStore(imageUrl)
    }

    /**
     * Downloads an image from a URL and stores it locally.
     * Returns the local file URI string, or null if download fails.
     *
     * If the URL is already a local file URI, returns it as-is.
     * If the URL uses HTTP and the download fails, retries with HTTPS.
     */
    suspend fun downloadAndStore(imageUrl: String): String? {
        return downloadAndStoreWithResult(imageUrl)?.localUri
    }

    /**
     * Downloads an image from a URL and stores it locally.
     * Returns a [DownloadResult] containing both the local file URI and the
     * effective URL that was used for the download, or null if download fails.
     *
     * If the URL is already a local file URI, returns it as-is.
     * If the URL uses HTTP and the download fails, retries with HTTPS.
     */
    suspend fun downloadAndStoreWithResult(imageUrl: String): DownloadResult? {
        // Skip if already a local file
        if (imageUrl.startsWith("file://")) {
            return DownloadResult(localUri = imageUrl, effectiveUrl = imageUrl)
        }

        // Try the original URL first
        attemptDownload(imageUrl)?.let { return it }

        // If the original URL was HTTP, retry with HTTPS
        if (imageUrl.startsWith("http://")) {
            val httpsUrl = imageUrl.replaceFirst("http://", "https://")
            Log.d(TAG, "Retrying image download with HTTPS: $httpsUrl")
            attemptDownload(httpsUrl)?.let {
                Log.d(TAG, "HTTPS retry succeeded for $httpsUrl")
                return it
            }
        }

        return null
    }

    /**
     * Attempts to download an image from the given URL and store it locally.
     * Returns a [DownloadResult] on success, or null on failure.
     */
    private suspend fun attemptDownload(imageUrl: String): DownloadResult? {
        return try {
            val response = httpClient.get(imageUrl) {
                header(HttpHeaders.UserAgent, "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                header(HttpHeaders.Accept, "image/*,*/*;q=0.8")
            }

            if (!response.status.isSuccess()) {
                Log.w(TAG, "Failed to download image from $imageUrl: HTTP ${response.status}")
                return null
            }

            // Determine file extension from content type
            val extension = when (response.contentType()?.contentSubtype) {
                "jpeg", "jpg" -> ".jpg"
                "png" -> ".png"
                "webp" -> ".webp"
                "gif" -> ".gif"
                else -> ".jpg" // Default to jpg
            }

            val fileName = "${UUID.randomUUID()}$extension"
            val imageFile = File(getImageDir(), fileName)

            response.bodyAsChannel().toInputStream().use { input ->
                imageFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // Verify the file was written and has content
            if (!imageFile.exists() || imageFile.length() == 0L) {
                Log.w(TAG, "Image file is empty after download: $imageUrl")
                imageFile.delete()
                return null
            }

            val localUri = "file://${imageFile.absolutePath}"
            Log.d(TAG, "Downloaded image: $imageUrl -> $localUri (${imageFile.length()} bytes)")
            DownloadResult(localUri = localUri, effectiveUrl = imageUrl)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download image from $imageUrl", e)
            null
        }
    }

    /**
     * Saves image bytes from an InputStream to local storage.
     * Returns the local file:// URI, or null if saving fails.
     *
     * @param inputStream Source of image bytes
     * @param extension File extension including the dot (e.g., ".jpg")
     */
    fun saveImageFromStream(inputStream: InputStream, extension: String = ".jpg"): String? {
        return try {
            val fileName = "${UUID.randomUUID()}$extension"
            val imageFile = File(getImageDir(), fileName)

            inputStream.use { input ->
                imageFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (!imageFile.exists() || imageFile.length() == 0L) {
                Log.w(TAG, "Image file is empty after saving from stream")
                imageFile.delete()
                return null
            }

            val localUri = "file://${imageFile.absolutePath}"
            Log.d(TAG, "Saved image from stream: $localUri (${imageFile.length()} bytes)")
            localUri
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save image from stream", e)
            null
        }
    }

    /**
     * Saves base64-encoded image data to local storage.
     * Returns the local file:// URI, or null if saving fails.
     */
    fun saveImageFromBase64(base64Data: String, extension: String = ".jpg"): String? {
        return try {
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            if (bytes.isEmpty()) {
                Log.w(TAG, "Base64 image data decoded to empty bytes")
                return null
            }

            val fileName = "${UUID.randomUUID()}$extension"
            val imageFile = File(getImageDir(), fileName)
            imageFile.writeBytes(bytes)

            val localUri = "file://${imageFile.absolutePath}"
            Log.d(TAG, "Saved image from base64: $localUri (${imageFile.length()} bytes)")
            localUri
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save image from base64", e)
            null
        }
    }

    /**
     * Returns the local File for a file:// URI, or null if the URI is not a local file
     * or the file doesn't exist.
     */
    fun getLocalImageFile(localUri: String): File? {
        if (!localUri.startsWith("file://")) return null
        val path = localUri.removePrefix("file://")
        val file = File(path)
        return if (file.exists() && file.parentFile?.name == IMAGE_DIR) file else null
    }

    /**
     * Deletes a locally stored image file.
     */
    fun deleteLocalImage(localUri: String) {
        if (!localUri.startsWith("file://")) return
        try {
            val path = localUri.removePrefix("file://")
            val file = File(path)
            if (file.exists() && file.parentFile?.name == IMAGE_DIR) {
                file.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete image: $localUri", e)
        }
    }
}
