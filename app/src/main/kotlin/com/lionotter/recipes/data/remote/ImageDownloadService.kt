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
 * After saving locally, uploads images to Firebase Storage so they sync across devices.
 * Returns a `gs://` URI that can be stored in the recipe's `imageUrl` field.
 *
 * All image processing flows should converge through [storeImage], which handles:
 * 1. Upload to Firebase Storage (with resize)
 * 2. Cleanup of old images (both local and remote)
 * 3. Returning the `gs://` URI for Firestore
 */
@Singleton
class ImageDownloadService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val httpClient: HttpClient,
    private val imageSyncService: ImageSyncService
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
    }

    // ─── Centralized image storage ───────────────────────────────────────

    /**
     * Upload a local image to Firebase Storage, optionally cleaning up the old image.
     *
     * This is the single entry point that all image flows should use after obtaining
     * a local file:// URI. It handles:
     * - Upload to Firebase Storage (with resizing if needed)
     * - Deletion of the previous image from both local cache and remote storage
     * - Returning the storage path for Firestore
     *
     * @param localUri A file:// URI pointing to the local image
     * @param previousImageUrl The previous imageUrl to clean up (gs:// URI or file:// URI), or null
     * @return A gs:// URI (e.g., "gs://bucket/users/{uid}/images/{filename}"),
     *         or the original local URI if upload fails
     */
    suspend fun storeImage(localUri: String, previousImageUrl: String? = null): String {
        // Already a storage path — no need to re-upload
        if (imageSyncService.isStoragePath(localUri)) return localUri

        // Clean up the old image if it's different from the new one
        if (previousImageUrl != null) {
            cleanupImage(previousImageUrl)
        }

        // Upload to Firebase Storage
        return imageSyncService.uploadImage(localUri) ?: localUri
    }

    /**
     * Delete an image from both local cache and Firebase Storage.
     */
    suspend fun cleanupImage(imageUrl: String) {
        if (imageSyncService.isStoragePath(imageUrl)) {
            imageSyncService.deleteRemoteImage(imageUrl)
            // Also delete local cache file
            val filename = imageUrl.substringAfterLast("/")
            val cacheFile = File(ImageCacheConfig.getImageCacheDir(context), filename)
            if (cacheFile.exists()) {
                cacheFile.delete()
            }
        } else if (imageUrl.startsWith("file://")) {
            deleteLocalImage(imageUrl)
        }
    }

    // ─── Image download ──────────────────────────────────────────────────

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
        // Firebase Storage paths are already stored — return as-is
        if (imageSyncService.isStoragePath(imageUrl)) return imageUrl
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

        // Firebase Storage paths are already stored — return as-is
        if (imageSyncService.isStoragePath(imageUrl)) {
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
            val imageFile = File(ImageCacheConfig.getImageCacheDir(context), fileName)

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

    // ─── Local image saving ──────────────────────────────────────────────

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
            val imageFile = File(ImageCacheConfig.getImageCacheDir(context), fileName)

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
            val imageFile = File(ImageCacheConfig.getImageCacheDir(context), fileName)
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
     * Saves an image from a content:// URI (e.g., from the image picker) to local storage.
     * Returns the local file:// URI, or null if saving fails.
     */
    fun saveImageFromContentUri(contentUri: android.net.Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(contentUri) ?: run {
                Log.w(TAG, "Could not open input stream for content URI: $contentUri")
                return null
            }

            // Determine extension from content type
            val mimeType = context.contentResolver.getType(contentUri)
            val extension = when (mimeType) {
                "image/png" -> ".png"
                "image/webp" -> ".webp"
                "image/gif" -> ".gif"
                "image/jpeg" -> ".jpg"
                else -> ".jpg"
            }

            saveImageFromStream(inputStream, extension)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save image from content URI: $contentUri", e)
            null
        }
    }

    // ─── Utility ─────────────────────────────────────────────────────────

    /**
     * Returns the local File for a file:// URI, or null if the URI is not a local file
     * or the file doesn't exist.
     */
    fun getLocalImageFile(localUri: String): File? {
        if (!localUri.startsWith("file://")) return null
        val path = localUri.removePrefix("file://")
        val file = File(path)
        return if (file.exists() && file.parentFile?.name == ImageCacheConfig.IMAGE_CACHE_DIR) file else null
    }

    /**
     * Deletes a locally stored image file.
     */
    fun deleteLocalImage(localUri: String) {
        if (!localUri.startsWith("file://")) return
        try {
            val path = localUri.removePrefix("file://")
            val file = File(path)
            if (file.exists() && file.parentFile?.name == ImageCacheConfig.IMAGE_CACHE_DIR) {
                file.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete image: $localUri", e)
        }
    }
}
