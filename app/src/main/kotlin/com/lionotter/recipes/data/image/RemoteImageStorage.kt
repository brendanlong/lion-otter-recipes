package com.lionotter.recipes.data.image

import java.io.File

/**
 * Cloud storage for the images of a user who syncs (signed in with Google). Stored images are
 * referenced by their `gs://` URL (e.g. `gs://bucket/users/{uid}/images/{filename}`), which is
 * what recipes save in `imageUrl`. Implemented by [FirebaseImageStorage].
 */
interface RemoteImageStorage {
    /** Uploads the local image [file] (resized first) and returns its storage URL, or null if that failed. */
    suspend fun upload(file: File): String?

    /** Deletes the stored image [storageUrl]. Best effort: failures are logged, not thrown. */
    suspend fun delete(storageUrl: String)

    /** The local cached copy of [storageUrl], downloaded first if needed; null if it isn't available. */
    suspend fun cachedFile(storageUrl: String): File?

    companion object {
        fun isStorageUrl(url: String?): Boolean = url != null && url.startsWith("gs://")
    }
}
