package com.lionotter.recipes.data.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.Source
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.storage
import com.google.firebase.storage.storageMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Shared constants and helpers for local image caching. */
object ImageCacheConfig {
    const val IMAGE_CACHE_DIR = "recipe_images"

    /** Maximum size of an image uploaded to Firebase Storage. */
    const val MAX_IMAGE_BYTES = 5 * 1024 * 1024L // 5 MB
    private const val TAG = "ImageCacheConfig"

    fun getImageCacheDir(context: Context): File {
        val dir = File(context.filesDir, IMAGE_CACHE_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Resolve a `gs://` URI to a local cache [File], downloading from Firebase Storage
     * if not already cached. The download is written to a temporary file and atomically
     * renamed, so a partially downloaded file is never served from the cache.
     * Returns `null` on failure.
     */
    suspend fun resolveToFile(gsUri: String, context: Context): File? = withContext(Dispatchers.IO) {
        val dir = getImageCacheDir(context)
        val cacheFile = File(dir, gsUri.substringAfterLast("/"))

        if (cacheFile.exists() && cacheFile.length() > 0) {
            return@withContext cacheFile
        }

        val tempFile = File(dir, ".${UUID.randomUUID()}.tmp")
        try {
            Firebase.storage.getReferenceFromUrl(gsUri).getFile(tempFile).await()
            if (tempFile.length() == 0L || !tempFile.renameTo(cacheFile)) {
                throw IOException("Failed to store downloaded image in cache")
            }
            Log.d(TAG, "Downloaded $gsUri to cache (${cacheFile.length()} bytes)")
            cacheFile
        } catch (e: StorageException) {
            if (e.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) {
                Log.d(TAG, "Image not found in storage: $gsUri")
            } else {
                Log.w(TAG, "Failed to download image: $gsUri", e)
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download image: $gsUri", e)
            null
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    /** Deletes every file in the local image cache. */
    suspend fun clearCache(context: Context) = withContext(Dispatchers.IO) {
        try {
            val files = getImageCacheDir(context).listFiles() ?: return@withContext
            val deletedCount = files.count { it.delete() }
            Log.d(TAG, "Cleared $deletedCount files from local image cache")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear local image cache", e)
        }
    }
}

/**
 * Syncs recipe images between local storage and Firebase Storage.
 *
 * Images are stored in Firebase Storage and referenced via `gs://` URIs
 * (e.g., `gs://bucket/users/{uid}/images/{filename}`). This makes the bucket
 * explicit and produces a well-formed URI with a scheme that Coil can match on.
 *
 * Strategy:
 * - On save: upload local image to Firebase Storage, return a `gs://` URI
 *   that gets stored in the recipe's `imageUrl` field in Firestore
 * - On display: if imageUrl is a `gs://` URI, check local cache first;
 *   if not cached, download from Firebase Storage to local cache
 * - Images are EXIF-rotated and resized before upload to keep retina quality but
 *   avoid excessively large files (max 2048px on longest side, max 5 MB)
 * - Orphaned images in Storage are cleaned up when recipes are deleted
 *
 * All public functions are main-safe.
 */
@Singleton
class ImageSyncService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firestoreService: FirestoreService
) {
    companion object {
        private const val TAG = "ImageSyncService"
        internal const val MAX_IMAGE_DIMENSION = 2048
        private val JPEG_QUALITIES = listOf(85, 75, 60, 45)
        private val PASSTHROUGH_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")

        /**
         * Largest power-of-2 sample size that keeps the longest side at or above
         * [targetSize], so the decoded bitmap only needs a final downscale.
         */
        internal fun calculateInSampleSize(width: Int, height: Int, targetSize: Int): Int {
            val longest = maxOf(width, height)
            var inSampleSize = 1
            while (longest / (inSampleSize * 2) >= targetSize) {
                inSampleSize *= 2
            }
            return inSampleSize
        }
    }

    private val storage by lazy { Firebase.storage }
    private val pendingUploadMutex = Mutex()

    private fun requireUid(): String {
        return Firebase.auth.currentUser?.uid
            ?: throw IllegalStateException("User not authenticated")
    }

    /**
     * Upload a local image file to Firebase Storage, normalizing orientation and
     * size first.
     *
     * @param localUri A file:// URI pointing to the local image
     * @return A gs:// URI (e.g., "gs://bucket/users/{uid}/images/{filename}")
     *         or null if upload fails
     */
    suspend fun uploadImage(localUri: String): String? = withContext(Dispatchers.IO) {
        if (!localUri.startsWith("file://")) {
            Log.w(TAG, "Not a local file URI: $localUri")
            return@withContext null
        }

        val file = File(localUri.removePrefix("file://"))
        if (!file.exists()) {
            Log.w(TAG, "Local file does not exist: ${file.path}")
            return@withContext null
        }

        try {
            val uid = requireUid()
            val upload = prepareForUpload(file)
            val storagePath = "users/$uid/images/${file.name}"
            val ref = storage.reference.child(storagePath)

            ref.putBytes(upload.bytes, storageMetadata { contentType = upload.mimeType }).await()
            val gsUri = "gs://${storage.reference.bucket}/$storagePath"
            Log.d(TAG, "Uploaded image to $gsUri (${upload.bytes.size} bytes)")
            gsUri
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload image: $localUri", e)
            null
        }
    }

    /**
     * Uploads every recipe image that is still a local `file://` URI (e.g. images
     * added while in guest mode) and points the recipe and its meal plans at the
     * uploaded `gs://` URI. Idempotent and safe to call repeatedly; runs to
     * completion even if the caller is cancelled.
     *
     * The recipe is only pointed at the upload if its image is still the local file, so a
     * newer image picked meanwhile is never reverted.
     */
    suspend fun uploadPendingLocalImages(): LocalImageUploadResult = withContext(NonCancellable + Dispatchers.IO) {
        pendingUploadMutex.withLock {
            val uid = googleUid()
            if (uid == null) {
                Log.d(TAG, "Not signed in with Google; skipping local image upload")
                return@withLock LocalImageUploadResult(uploaded = 0, failed = 0)
            }

            val recipes = firestoreService.recipesCollection(uid).get().await().documents
            var uploaded = 0
            var failed = 0
            for (doc in recipes) {
                val localUri = doc.getString("imageUrl")?.takeIf { it.startsWith("file://") } ?: continue
                if (!localFileExists(localUri)) {
                    Log.w(TAG, "Local image for recipe ${doc.id} no longer exists; nothing to upload")
                    continue
                }
                val gsUri = uploadImage(localUri)
                if (gsUri == null) {
                    Log.w(TAG, "Could not upload local image for recipe ${doc.id}")
                    failed++
                    continue
                }
                try {
                    val applied = doc.reference.firestore.runTransaction { tx ->
                        val current = tx.get(doc.reference).getString("imageUrl")
                        if (current == localUri) tx.update(doc.reference, "imageUrl", gsUri)
                        current == localUri
                    }.await()
                    if (applied) {
                        updateMealPlanImages(uid, localUri, gsUri)
                        uploaded++
                    } else {
                        Log.i(TAG, "Recipe ${doc.id} image changed during upload; discarding the upload")
                        if (!isStorageObjectReferenced(uid, gsUri)) deleteRemoteImage(gsUri)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Failed to point recipe ${doc.id} at its uploaded image", e)
                    failed++
                }
            }
            Log.i(TAG, "Uploaded $uploaded local images ($failed failed)")
            LocalImageUploadResult(uploaded = uploaded, failed = failed)
        }
    }

    data class LocalImageUploadResult(val uploaded: Int, val failed: Int)

    /**
     * True if a Google user has recipes whose image is still only on this device
     * (a `file://` that exists locally), i.e. would be lost by clearing local data.
     * Returns true if this can't be determined.
     */
    suspend fun hasPendingLocalImages(): Boolean = withContext(Dispatchers.IO) {
        val uid = googleUid() ?: return@withContext false
        try {
            firestoreService.recipesCollection(uid).get(Source.CACHE).await().documents.any { doc ->
                doc.getString("imageUrl")?.takeIf { it.startsWith("file://") }?.let(::localFileExists) == true
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Could not check for images pending upload", e)
            true
        }
    }

    private fun googleUid(): String? = Firebase.auth.currentUser?.takeUnless { it.isAnonymous }?.uid

    private fun localFileExists(localUri: String): Boolean = File(localUri.removePrefix("file://")).exists()

    private suspend fun isStorageObjectReferenced(uid: String, gsUri: String): Boolean = try {
        !firestoreService.recipesCollection(uid).whereEqualTo("imageUrl", gsUri).get().await().isEmpty
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.w(TAG, "Could not check whether $gsUri is in use; keeping it", e)
        true
    }

    private suspend fun updateMealPlanImages(uid: String, localUri: String, gsUri: String) {
        try {
            val mealPlans = firestoreService.mealPlansCollection(uid)
                .whereEqualTo("recipeImageUrl", localUri)
                .get()
                .await()
            for (mealPlan in mealPlans.documents) {
                mealPlan.reference.update("recipeImageUrl", gsUri)
                    .addOnFailureListener { e -> Log.w(TAG, "Failed to update meal plan image ${mealPlan.id}", e) }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Failed to update meal plan images for $localUri", e)
        }
    }

    /**
     * Ensure a local cached copy of an image exists.
     * If the imageUrl is a Firebase Storage URI, downloads it to local cache.
     * If it's already a local file://, returns it as-is (if it exists).
     *
     * @param imageUrl A gs:// URI or local file:// URI
     * @return A local file:// URI, or null if the image cannot be resolved
     */
    suspend fun ensureLocalImage(imageUrl: String?): String? {
        if (imageUrl == null) return null

        if (imageUrl.startsWith("file://")) {
            val path = imageUrl.removePrefix("file://")
            return if (withContext(Dispatchers.IO) { File(path).exists() }) imageUrl else null
        }

        if (isStoragePath(imageUrl)) {
            val cacheFile = ImageCacheConfig.resolveToFile(imageUrl, context) ?: return null
            return "file://${cacheFile.absolutePath}"
        }

        // Remote URL — not handled here (ImageDownloadService handles these)
        return null
    }

    /**
     * Delete an image from Firebase Storage.
     * Best-effort — logs but does not propagate failures.
     *
     * @param imageUrl A gs:// URI
     */
    suspend fun deleteRemoteImage(imageUrl: String) {
        if (!isStoragePath(imageUrl)) return
        try {
            storage.getReferenceFromUrl(imageUrl).delete().await()
            Log.d(TAG, "Deleted remote image: $imageUrl")
        } catch (e: StorageException) {
            if (e.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) {
                Log.d(TAG, "Remote image already deleted: $imageUrl")
            } else {
                Log.w(TAG, "Failed to delete remote image: $imageUrl", e)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete remote image: $imageUrl", e)
        }
    }

    internal class PreparedImage(val bytes: ByteArray, val mimeType: String)

    /**
     * Returns upload-ready bytes: the original file when it is already a small,
     * upright JPEG/PNG/WebP, otherwise a re-encoded JPEG that is EXIF-rotated, scaled to
     * at most [MAX_IMAGE_DIMENSION] on its longest side and no larger than
     * [ImageCacheConfig.MAX_IMAGE_BYTES].
     *
     * @throws IOException if the image can't be decoded or compressed small enough
     */
    internal suspend fun prepareForUpload(file: File): PreparedImage = withContext(Dispatchers.Default) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) {
            throw IOException("Unsupported image format: ${bounds.outMimeType ?: "unknown"}")
        }

        val orientation = readExifOrientation(file)
        val isUpright = orientation == ExifInterface.ORIENTATION_NORMAL ||
            orientation == ExifInterface.ORIENTATION_UNDEFINED
        val mimeType: String? = bounds.outMimeType
        if (isUpright && maxOf(width, height) <= MAX_IMAGE_DIMENSION &&
            file.length() <= ImageCacheConfig.MAX_IMAGE_BYTES &&
            mimeType != null && mimeType in PASSTHROUGH_MIME_TYPES
        ) {
            return@withContext PreparedImage(file.readBytes(), mimeType)
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(width, height, MAX_IMAGE_DIMENSION)
        }
        val sampled = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
            ?: throw IOException("Failed to decode image")

        val scale = minOf(1f, MAX_IMAGE_DIMENSION.toFloat() / maxOf(sampled.width, sampled.height))
        val matrix = Matrix().apply {
            setScale(scale, scale)
            applyExifOrientation(orientation)
        }
        val bitmap = if (matrix.isIdentity) {
            sampled
        } else {
            Bitmap.createBitmap(sampled, 0, 0, sampled.width, sampled.height, matrix, true)
                .also { if (it !== sampled) sampled.recycle() }
        }

        try {
            for (quality in JPEG_QUALITIES) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= ImageCacheConfig.MAX_IMAGE_BYTES) {
                    Log.d(
                        TAG, "Prepared image: ${width}x$height (orientation $orientation) -> " +
                            "${bitmap.width}x${bitmap.height} q$quality, ${file.length()} -> ${out.size()} bytes"
                    )
                    return@withContext PreparedImage(out.toByteArray(), "image/jpeg")
                }
            }
            throw IOException("Image is too large to upload even after compression")
        } finally {
            bitmap.recycle()
        }
    }

    private fun readExifOrientation(file: File): Int = try {
        ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: Exception) {
        Log.w(TAG, "Could not read EXIF orientation for ${file.name}", e)
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun Matrix.applyExifOrientation(orientation: Int) {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                postRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                postRotate(-90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
        }
    }

    /**
     * Check if a URL is a Firebase Storage gs:// URI.
     */
    fun isStoragePath(url: String?): Boolean {
        return url != null && url.startsWith("gs://")
    }
}
