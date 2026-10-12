package com.lionotter.recipes.data.remote

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.work.WorkManager
import com.lionotter.recipes.util.ErrorLog
import com.lionotter.recipes.worker.LocalImageUploadWorker
import com.lionotter.recipes.data.remote.dto.RecipeFields
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
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
 *
 * All public functions are main-safe.
 */
@Singleton
class ImageDownloadService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val httpClient: HttpClient,
    private val imageSyncService: ImageSyncService,
    private val authService: AuthService,
    private val firestoreService: FirestoreService,
    private val workManager: Provider<WorkManager>
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

        /** Maximum size of an image downloaded or saved locally (before resizing for upload). */
        const val MAX_LOCAL_IMAGE_BYTES = 25 * 1024 * 1024L
    }

    // ─── Centralized image storage ───────────────────────────────────────

    /**
     * Upload a local image to Firebase Storage, then clean up the old image.
     *
     * This is the single entry point that all image flows should use after obtaining
     * a local file:// URI. It handles:
     * - Upload to Firebase Storage (with resizing if needed)
     * - Deletion of the previous image from both local cache and remote storage,
     *   only after the new image has been uploaded and only if no other recipe uses it
     * - Returning the storage path for Firestore
     *
     * If a Google user's upload fails, the local URI is returned, the previous image is
     * kept, and [LocalImageUploadWorker] is scheduled to retry the upload.
     *
     * @param localUri A file:// URI pointing to the local image
     * @param previousImageUrl The previous imageUrl to clean up (gs:// URI or file:// URI), or null
     * @param recipeId The recipe the image belongs to; it doesn't count as another user of
     *        [previousImageUrl] when deciding whether that can be deleted
     * @return A gs:// URI (e.g., "gs://bucket/users/{uid}/images/{filename}"),
     *         or the original local URI if the user is a guest or upload fails
     */
    suspend fun storeImage(localUri: String, previousImageUrl: String? = null, recipeId: String? = null): String {
        // Already a storage path — no need to re-upload
        if (imageSyncService.isStoragePath(localUri)) return localUri

        // Guests keep the local file; it is uploaded after sign-in
        // (see ImageSyncService.uploadPendingLocalImages).
        if (!authService.isGoogleUser()) {
            cleanupPrevious(previousImageUrl, localUri, recipeId)
            return localUri
        }

        val uploaded = imageSyncService.uploadImage(localUri)
        if (uploaded == null) {
            Log.w(TAG, "Image upload failed; keeping the local copy and scheduling a retry")
            scheduleLocalImageUpload()
            return localUri
        }
        cleanupPrevious(previousImageUrl, uploaded, recipeId)
        return uploaded
    }

    private suspend fun cleanupPrevious(previousImageUrl: String?, stored: String, recipeId: String?) {
        if (previousImageUrl != null && previousImageUrl != stored) {
            cleanupImage(previousImageUrl, recipeId)
        }
    }

    private fun scheduleLocalImageUpload() {
        try {
            LocalImageUploadWorker.enqueue(workManager.get())
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Failed to schedule local image upload", e)
        }
    }

    /**
     * Delete an image from both local cache and Firebase Storage, unless a recipe other
     * than [recipeId] still references it (e.g. a recipe saved as a copy shares its image).
     */
    suspend fun cleanupImage(imageUrl: String, recipeId: String? = null) {
        if (isReferencedByOtherRecipe(imageUrl, recipeId)) {
            Log.d(TAG, "Image is still used by another recipe; keeping it: $imageUrl")
            return
        }
        if (imageSyncService.isStoragePath(imageUrl)) {
            imageSyncService.deleteRemoteImage(imageUrl)
            val filename = imageUrl.substringAfterLast("/")
            withContext(Dispatchers.IO) {
                val cacheFile = File(ImageCacheConfig.getImageCacheDir(context), filename)
                if (cacheFile.exists() && !cacheFile.delete()) {
                    Log.w(TAG, "Failed to delete cached image: ${cacheFile.path}")
                }
            }
        } else if (imageUrl.startsWith("file://")) {
            deleteLocalImage(imageUrl)
        }
    }

    /** True if another recipe uses [imageUrl], or if that can't be determined (so it is kept). */
    private suspend fun isReferencedByOtherRecipe(imageUrl: String, recipeId: String?): Boolean {
        val uid = authService.authState.value.uid ?: return true
        return try {
            firestoreService.recipesCollection(uid)
                .whereEqualTo(RecipeFields.IMAGE_URL, imageUrl)
                .get()
                .await()
                .documents
                .any { it.id != recipeId }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not check whether $imageUrl is still in use; keeping it", e)
            true
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
            return if (withContext(Dispatchers.IO) { File(path).exists() }) imageUrl else null
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
     * Rejects non-image responses (e.g. HTML error pages) and bodies larger than
     * [MAX_LOCAL_IMAGE_BYTES]. Returns a [DownloadResult] on success, or null on failure.
     */
    private suspend fun attemptDownload(imageUrl: String): DownloadResult? {
        return try {
            httpClient.prepareGet(imageUrl) {
                header(HttpHeaders.UserAgent, "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                header(HttpHeaders.Accept, "image/*,*/*;q=0.8")
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    Log.w(TAG, "Failed to download image from $imageUrl: HTTP ${response.status}")
                    return@execute null
                }

                val contentType = response.contentType()
                if (contentType != null && !isAcceptableImageContentType(contentType)) {
                    Log.w(TAG, "Not an image ($contentType): $imageUrl")
                    return@execute null
                }
                val declaredLength = response.contentLength()
                if (declaredLength != null && declaredLength > MAX_LOCAL_IMAGE_BYTES) {
                    Log.w(TAG, "Image too large ($declaredLength bytes): $imageUrl")
                    return@execute null
                }

                val extension = when (contentType?.contentSubtype) {
                    "png" -> ".png"
                    "webp" -> ".webp"
                    "gif" -> ".gif"
                    else -> ".jpg"
                }
                val input = response.bodyAsChannel().toInputStream()
                writeValidatedImage(input, extension)?.let { localUri ->
                    Log.d(TAG, "Downloaded image: $imageUrl -> $localUri")
                    DownloadResult(localUri = localUri, effectiveUrl = imageUrl)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download image from $imageUrl", e)
            null
        }
    }

    private fun isAcceptableImageContentType(contentType: ContentType): Boolean =
        contentType.contentType == "image" ||
            contentType.match(ContentType.Application.OctetStream)

    // ─── Local image saving ──────────────────────────────────────────────

    /**
     * Saves image bytes from an InputStream to local storage.
     * Returns the local file:// URI, or null if saving fails or the data is not an image.
     *
     * @param inputStream Source of image bytes (closed by this function)
     * @param extension File extension including the dot (e.g., ".jpg")
     */
    suspend fun saveImageFromStream(inputStream: InputStream, extension: String = ".jpg"): String? {
        return try {
            inputStream.use { writeValidatedImage(it, extension) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save image from stream", e)
            null
        }
    }

    /**
     * Saves base64-encoded image data to local storage.
     * Returns the local file:// URI, or null if saving fails.
     */
    suspend fun saveImageFromBase64(base64Data: String, extension: String = ".jpg"): String? {
        return try {
            val bytes = withContext(Dispatchers.Default) { Base64.decode(base64Data, Base64.DEFAULT) }
            if (bytes.isEmpty()) {
                Log.w(TAG, "Base64 image data decoded to empty bytes")
                return null
            }
            writeValidatedImage(bytes.inputStream(), extension)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save image from base64", e)
            null
        }
    }

    /**
     * Saves an image from a content:// URI (e.g., from the image picker) to local storage.
     * Returns the local file:// URI, or null if saving fails.
     */
    suspend fun saveImageFromContentUri(contentUri: android.net.Uri): String? {
        return try {
            val saved = withContext(Dispatchers.IO) {
                val extension = when (context.contentResolver.getType(contentUri)) {
                    "image/png" -> ".png"
                    "image/webp" -> ".webp"
                    "image/gif" -> ".gif"
                    "image/heic", "image/heif" -> ".heic"
                    else -> ".jpg"
                }
                context.contentResolver.openInputStream(contentUri)?.use { saveImageFromStream(it, extension) }
            }
            if (saved == null) {
                Log.w(TAG, "Could not open content URI: $contentUri")
            }
            saved
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save image from content URI: $contentUri", e)
            null
        }
    }

    /**
     * Copies [input] into a new file in the image cache, enforcing [MAX_LOCAL_IMAGE_BYTES]
     * and checking the result decodes as an image. Returns the file:// URI, or null.
     */
    private suspend fun writeValidatedImage(input: InputStream, extension: String): String? =
        withContext(Dispatchers.IO) {
            val imageFile = File(ImageCacheConfig.getImageCacheDir(context), "${UUID.randomUUID()}$extension")
            try {
                imageFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_LOCAL_IMAGE_BYTES) {
                            throw IOException("Image exceeds $MAX_LOCAL_IMAGE_BYTES bytes")
                        }
                        output.write(buffer, 0, read)
                    }
                }

                if (imageFile.length() == 0L) {
                    Log.w(TAG, "Image file is empty after saving")
                    imageFile.delete()
                    return@withContext null
                }

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    Log.w(TAG, "Saved data is not a decodable image")
                    imageFile.delete()
                    return@withContext null
                }

                "file://${imageFile.absolutePath}"
            } catch (e: Exception) {
                imageFile.delete()
                throw e
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
    suspend fun deleteLocalImage(localUri: String) {
        if (!localUri.startsWith("file://")) return
        withContext(Dispatchers.IO) {
            try {
                val file = File(localUri.removePrefix("file://"))
                if (file.exists() && file.parentFile?.name == ImageCacheConfig.IMAGE_CACHE_DIR) {
                    file.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete image: $localUri", e)
            }
        }
    }
}
