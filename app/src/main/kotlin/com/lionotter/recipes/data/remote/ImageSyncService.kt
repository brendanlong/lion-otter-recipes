package com.lionotter.recipes.data.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.storage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Shared constants and helpers for local image caching. */
object ImageCacheConfig {
    const val IMAGE_CACHE_DIR = "recipe_images"
    const val MAX_IMAGE_BYTES = 5 * 1024 * 1024L // 5 MB

    fun getImageCacheDir(context: Context): File {
        val dir = File(context.filesDir, IMAGE_CACHE_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
}

/**
 * Syncs recipe images between local storage and Firebase Storage.
 *
 * Storage path: `users/{userId}/images/{filename}`
 *
 * Strategy:
 * - On save: upload local image to Firebase Storage, update recipe's imageUrl
 *   to the storage path (e.g., "users/{uid}/images/{filename}") so it syncs via Firestore
 * - On display: if imageUrl is a storage path, check local cache first;
 *   if not cached, download from Firebase Storage to local cache
 * - Images are resized before upload to keep retina quality but avoid
 *   excessively large files (max 2048px on longest side)
 * - Orphaned images in Storage are cleaned up when recipes are deleted
 */
@Singleton
class ImageSyncService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ImageSyncService"
        private const val MAX_IMAGE_DIMENSION = 2048
        private const val JPEG_QUALITY = 85
    }

    private val storage = Firebase.storage

    private fun requireUid(): String {
        return Firebase.auth.currentUser?.uid
            ?: throw IllegalStateException("User not authenticated")
    }

    /**
     * Upload a local image file to Firebase Storage.
     * Resizes the image if it exceeds [MAX_IMAGE_DIMENSION] on its longest side.
     *
     * @param localUri A file:// URI pointing to the local image
     * @return The Firebase Storage path (e.g., "users/{uid}/images/{filename}")
     *         or null if upload fails
     */
    suspend fun uploadImage(localUri: String): String? {
        if (!localUri.startsWith("file://")) {
            Log.w(TAG, "Not a local file URI: $localUri")
            return null
        }

        val path = localUri.removePrefix("file://")
        val file = File(path)
        if (!file.exists()) {
            Log.w(TAG, "Local file does not exist: $path")
            return null
        }

        return try {
            val uid = requireUid()
            val imageBytes = resizeImageIfNeeded(file)
            val storagePath = "users/$uid/images/${file.name}"
            val ref = storage.reference.child(storagePath)

            ref.putBytes(imageBytes).await()
            Log.d(TAG, "Uploaded image to $storagePath (${imageBytes.size} bytes)")
            storagePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload image: $localUri", e)
            null
        }
    }

    /**
     * Ensure a local cached copy of an image exists.
     * If the imageUrl is a Firebase Storage path, downloads it to local cache.
     * If it's already a local file://, returns it as-is (if it exists).
     *
     * @param imageUrl Either a Firebase Storage path or a local file:// URI
     * @return A local file:// URI, or null if the image cannot be resolved
     */
    suspend fun ensureLocalImage(imageUrl: String?): String? {
        if (imageUrl == null) return null

        // Already a local file — check it exists
        if (imageUrl.startsWith("file://")) {
            val path = imageUrl.removePrefix("file://")
            return if (File(path).exists()) imageUrl else null
        }

        // Firebase Storage path (users/{uid}/images/{filename})
        if (imageUrl.startsWith("users/")) {
            return downloadToCache(imageUrl)
        }

        // Remote URL — not handled here (ImageDownloadService handles these)
        return null
    }

    /**
     * Download an image from Firebase Storage to the local cache directory.
     * Returns the local file:// URI, or null if download fails.
     * If the file is already cached, returns the existing cache path.
     */
    private suspend fun downloadToCache(storagePath: String): String? {
        val filename = storagePath.substringAfterLast("/")
        val cacheFile = File(ImageCacheConfig.getImageCacheDir(context), filename)

        // Already cached
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return "file://${cacheFile.absolutePath}"
        }

        return try {
            val ref = storage.reference.child(storagePath)
            val bytes = ref.getBytes(ImageCacheConfig.MAX_IMAGE_BYTES).await()
            cacheFile.writeBytes(bytes)
            Log.d(TAG, "Downloaded image from $storagePath to cache (${bytes.size} bytes)")
            "file://${cacheFile.absolutePath}"
        } catch (e: StorageException) {
            if (e.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) {
                Log.d(TAG, "Image not found in storage: $storagePath")
            } else {
                Log.w(TAG, "Failed to download image: $storagePath", e)
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download image: $storagePath", e)
            null
        }
    }

    /**
     * Delete an image from Firebase Storage.
     * Best-effort — logs but does not propagate failures.
     */
    suspend fun deleteRemoteImage(storagePath: String) {
        if (!storagePath.startsWith("users/")) return
        try {
            storage.reference.child(storagePath).delete().await()
            Log.d(TAG, "Deleted remote image: $storagePath")
        } catch (e: StorageException) {
            if (e.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) {
                Log.d(TAG, "Remote image already deleted: $storagePath")
            } else {
                Log.w(TAG, "Failed to delete remote image: $storagePath", e)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete remote image: $storagePath", e)
        }
    }

    /**
     * Resize an image file if it exceeds [MAX_IMAGE_DIMENSION] on its longest side.
     * Returns the (potentially resized) image bytes as JPEG.
     */
    private fun resizeImageIfNeeded(file: File): ByteArray {
        // First, decode just the bounds to check dimensions
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)

        val originalWidth = options.outWidth
        val originalHeight = options.outHeight

        if (originalWidth <= 0 || originalHeight <= 0) {
            // Can't decode as bitmap — return raw bytes
            return file.readBytes()
        }

        val longestSide = maxOf(originalWidth, originalHeight)
        if (longestSide <= MAX_IMAGE_DIMENSION && file.length() <= ImageCacheConfig.MAX_IMAGE_BYTES) {
            // No resize needed — return original bytes
            return file.readBytes()
        }

        // Calculate sample size for efficient decoding
        val sampleSize = calculateInSampleSize(originalWidth, originalHeight, MAX_IMAGE_DIMENSION)
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val sampledBitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
            ?: return file.readBytes()

        // Scale to exact target dimensions if still too large
        val bitmap = if (maxOf(sampledBitmap.width, sampledBitmap.height) > MAX_IMAGE_DIMENSION) {
            val scale = MAX_IMAGE_DIMENSION.toFloat() / maxOf(sampledBitmap.width, sampledBitmap.height)
            val targetWidth = (sampledBitmap.width * scale).toInt()
            val targetHeight = (sampledBitmap.height * scale).toInt()
            val scaled = sampledBitmap.scale(targetWidth, targetHeight)
            if (scaled !== sampledBitmap) sampledBitmap.recycle()
            scaled
        } else {
            sampledBitmap
        }

        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
        bitmap.recycle()

        Log.d(
            TAG, "Resized image: ${originalWidth}x${originalHeight} -> " +
                "${bitmap.width}x${bitmap.height}, ${file.length()} -> ${baos.size()} bytes"
        )
        return baos.toByteArray()
    }

    /**
     * Calculate the largest inSampleSize value that is a power of 2 and keeps both
     * the width and height larger than [targetSize].
     */
    private fun calculateInSampleSize(width: Int, height: Int, targetSize: Int): Int {
        var inSampleSize = 1
        if (width > targetSize || height > targetSize) {
            val halfWidth = width / 2
            val halfHeight = height / 2
            while ((halfWidth / inSampleSize) >= targetSize && (halfHeight / inSampleSize) >= targetSize) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * Check if a URL is a Firebase Storage path (as opposed to a local file or remote URL).
     */
    fun isStoragePath(url: String?): Boolean {
        return url != null && url.startsWith("users/")
    }
}
