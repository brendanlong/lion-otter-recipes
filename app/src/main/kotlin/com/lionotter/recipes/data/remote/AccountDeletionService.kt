package com.lionotter.recipes.data.remote

import android.content.Context
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.firestore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles complete deletion of a user's account and all associated data.
 *
 * Deletes (in order):
 * 1. Reads image URLs from all recipes (before deleting Firestore docs)
 * 2. Deletes each image from Firebase Storage individually
 * 3. Clears local image cache
 * 4. Deletes all recipes from Firestore
 * 5. Deletes all meal plans from Firestore
 * 6. Deletes the user document from Firestore
 * 7. Deletes the Firebase Auth account
 *
 * Image URLs are collected from Firestore recipes before any documents are
 * deleted, then each image is deleted individually from Storage. This avoids
 * needing the `list` permission on Storage (which `listAll()` requires).
 */
@Singleton
class AccountDeletionService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firestoreService: FirestoreService,
    private val imageSyncService: ImageSyncService
) {
    companion object {
        private const val TAG = "AccountDeletionService"
    }

    /**
     * Deletes all user data from Firebase and the local device, then deletes
     * the Firebase Auth account.
     *
     * @throws Exception if a critical step fails (errors in non-critical steps
     *         are logged but do not prevent subsequent steps from running)
     */
    suspend fun deleteAllUserData() {
        val user = Firebase.auth.currentUser
            ?: throw IllegalStateException("No authenticated user")
        val uid = user.uid

        Log.i(TAG, "Starting account deletion for user $uid")

        // 1. Read image URLs from all recipes BEFORE deleting any documents.
        //    We need these URLs to delete the actual files from Firebase Storage.
        val imageUrls = collectImageUrls()

        // 2. Delete each image from Firebase Storage individually.
        //    This uses direct gs:// URIs (only needs `delete` permission, not `list`).
        deleteStorageImages(imageUrls)

        // 3. Clear local image cache
        clearLocalImageCache()

        // 4. Delete all recipes from Firestore
        deleteCollection(firestoreService.recipesCollection(uid))

        // 5. Delete all meal plans from Firestore
        deleteCollection(firestoreService.mealPlansCollection(uid))

        // 6. Delete the user document itself (may not exist as a standalone doc,
        //    but clean up just in case)
        try {
            Firebase.firestore.collection(FirestoreService.USERS_COLLECTION)
                .document(uid)
                .delete()
                .await()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete user document (may not exist)", e)
        }

        // 7. Delete Firebase Auth account
        try {
            user.delete().await()
            Log.i(TAG, "Firebase Auth account deleted")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete Firebase Auth account (may require re-authentication)", e)
            // This is non-fatal — the data is already deleted.
            // Firebase Auth accounts for anonymous users or users who haven't
            // recently authenticated may fail here, which is acceptable.
        }

        Log.i(TAG, "Account deletion completed for user $uid")
    }

    /**
     * Deletes all documents in a Firestore collection using batched writes.
     * Firestore limits batches to 500 operations, so this processes in chunks.
     */
    private suspend fun deleteCollection(collection: com.google.firebase.firestore.CollectionReference) {
        try {
            val snapshot = collection.get().await()
            if (snapshot.documents.isEmpty()) return

            // Firestore batch limit is 500 operations
            snapshot.documents.chunked(500).forEach { chunk ->
                val batch = Firebase.firestore.batch()
                for (doc in chunk) {
                    batch.delete(doc.reference)
                }
                batch.commit().await()
            }
            Log.d(TAG, "Deleted ${snapshot.documents.size} documents from ${collection.path}")
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting collection ${collection.path}", e)
            throw e
        }
    }

    /**
     * Reads all recipe documents and collects their image URLs (gs:// paths).
     * This must be called before deleting Firestore documents.
     */
    private suspend fun collectImageUrls(): List<String> {
        return try {
            val uid = Firebase.auth.currentUser?.uid
                ?: throw IllegalStateException("No authenticated user for image collection")
            val snapshot = firestoreService.recipesCollection(uid).get().await()
            snapshot.documents.mapNotNull { doc ->
                doc.getString("imageUrl")?.takeIf { imageSyncService.isStoragePath(it) }
            }.also { urls ->
                Log.d(TAG, "Collected ${urls.size} image URLs from ${snapshot.documents.size} recipes")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to collect image URLs from recipes", e)
            emptyList()
        }
    }

    /**
     * Deletes each image from Firebase Storage individually using its gs:// URI.
     * This only requires `delete` permission, unlike `listAll()` which requires
     * the `list` permission.
     */
    private suspend fun deleteStorageImages(imageUrls: List<String>) {
        var deletedCount = 0
        for (url in imageUrls) {
            try {
                imageSyncService.deleteRemoteImage(url)
                deletedCount++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete storage image: $url", e)
            }
        }
        Log.d(TAG, "Deleted $deletedCount/${imageUrls.size} images from Firebase Storage")
    }

    /**
     * Clears the local recipe image cache directory.
     */
    private fun clearLocalImageCache() {
        try {
            val cacheDir = ImageCacheConfig.getImageCacheDir(context)
            val files = cacheDir.listFiles() ?: return
            var deletedCount = 0
            for (file in files) {
                if (file.delete()) deletedCount++
            }
            Log.d(TAG, "Cleared $deletedCount files from local image cache")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear local image cache", e)
        }
    }
}
