package com.lionotter.recipes.data.image

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.storage
import com.google.firebase.storage.storageMetadata
import com.lionotter.recipes.util.ErrorLog
import com.lionotter.recipes.util.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [RemoteImageStorage] in Firebase Storage. Downloads are cached in [LocalImageFiles] (shared
 * with the Coil fetcher), so an image is downloaded once.
 *
 * All functions are main-safe.
 */
@Singleton
class FirebaseImageStorage @Inject constructor(
    private val localImageFiles: LocalImageFiles,
    private val uploadPreparer: ImageUploadPreparer,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : RemoteImageStorage {
    companion object {
        private const val TAG = "FirebaseImageStorage"
    }

    private val storage by lazy { Firebase.storage }

    /** Uploads to `users/{uid}/images/{file name}`, so the local file doubles as the cached copy. */
    override suspend fun upload(file: File): String? = withContext(ioDispatcher) {
        if (!file.exists()) {
            Log.w(TAG, "Local file does not exist: ${file.path}")
            return@withContext null
        }
        try {
            val uid = Firebase.auth.currentUser?.uid ?: throw IllegalStateException("User not authenticated")
            val upload = uploadPreparer.prepare(file)
            val storagePath = "users/$uid/images/${file.name}"
            storage.reference.child(storagePath)
                .putBytes(upload.bytes, storageMetadata { contentType = upload.mimeType })
                .await()
            val gsUri = "gs://${storage.reference.bucket}/$storagePath"
            Log.d(TAG, "Uploaded image to $gsUri (${upload.bytes.size} bytes)")
            gsUri
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Failed to upload image: ${file.name}", e)
            null
        }
    }

    override suspend fun delete(storageUrl: String) {
        if (!RemoteImageStorage.isStorageUrl(storageUrl)) return
        try {
            storage.getReferenceFromUrl(storageUrl).delete().await()
            Log.d(TAG, "Deleted remote image: $storageUrl")
        } catch (e: StorageException) {
            if (e.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) {
                Log.d(TAG, "Remote image already deleted: $storageUrl")
            } else {
                Log.w(TAG, "Failed to delete remote image: $storageUrl", e)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete remote image: $storageUrl", e)
        }
    }

    override suspend fun cachedFile(storageUrl: String): File? = withContext(ioDispatcher) {
        val cacheFile = localImageFiles.cacheFileFor(storageUrl)
        if (cacheFile.exists() && cacheFile.length() > 0) return@withContext cacheFile
        try {
            val downloaded = localImageFiles.writeAtomically(cacheFile) { temp ->
                storage.getReferenceFromUrl(storageUrl).getFile(temp).await()
                if (temp.length() == 0L) throw IOException("Downloaded image is empty")
                true
            }
            if (downloaded) {
                Log.d(TAG, "Downloaded $storageUrl to cache (${cacheFile.length()} bytes)")
                cacheFile
            } else {
                null
            }
        } catch (e: StorageException) {
            if (e.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) {
                Log.d(TAG, "Image not found in storage: $storageUrl")
            } else {
                Log.w(TAG, "Failed to download image: $storageUrl", e)
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download image: $storageUrl", e)
            null
        }
    }
}
