package com.lionotter.recipes.data.remote

import android.content.Context
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.firestore
import com.google.firebase.storage.storage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles complete deletion of a user's account and all associated data.
 *
 * Deletes (in order):
 * 1. All files from Firebase Storage (entire `users/{uid}/` tree)
 * 2. Local image cache
 * 3. All recipes from Firestore
 * 4. All meal plans from Firestore
 * 5. Firebase Auth account
 *
 * Storage files are deleted before Firestore documents to ensure security
 * rules that reference Firestore data are still satisfied during deletion.
 */
@Singleton
class AccountDeletionService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firestoreService: FirestoreService
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

        // 1. Delete all files from Firebase Storage first, before removing
        //    Firestore documents that may be referenced by Storage security rules
        deleteStorageFiles(uid)

        // 2. Clear local image cache
        clearLocalImageCache()

        // 3. Delete all recipes from Firestore
        deleteCollection(firestoreService.recipesCollection())

        // 4. Delete all meal plans from Firestore
        deleteCollection(firestoreService.mealPlansCollection())

        // 5. Delete the user document itself (may not exist as a standalone doc,
        //    but clean up just in case)
        try {
            Firebase.firestore.collection(FirestoreService.USERS_COLLECTION)
                .document(uid)
                .delete()
                .await()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete user document (may not exist)", e)
        }

        // 6. Delete Firebase Auth account
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
     * Deletes all files from Firebase Storage for the given user.
     * Lists from `users/{uid}/` to catch everything under the user's folder,
     * not just the known `images/` subfolder.
     */
    private suspend fun deleteStorageFiles(uid: String) {
        try {
            val userRef = Firebase.storage.reference.child("users/$uid")
            deleteStoragePrefix(userRef)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to list/delete storage files for user $uid", e)
            // Non-fatal: storage cleanup is best-effort since the auth account
            // will be deleted and security rules will prevent future access
        }
    }

    /**
     * Recursively deletes all files under a Firebase Storage prefix.
     * `listAll()` returns items (files) at the current level and prefixes
     * (subdirectories) that need to be traversed separately.
     */
    private suspend fun deleteStoragePrefix(ref: com.google.firebase.storage.StorageReference) {
        val listResult = ref.listAll().await()

        for (item in listResult.items) {
            try {
                item.delete().await()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete storage file: ${item.path}", e)
            }
        }

        for (prefix in listResult.prefixes) {
            deleteStoragePrefix(prefix)
        }

        if (listResult.items.isNotEmpty()) {
            Log.d(TAG, "Deleted ${listResult.items.size} files from ${ref.path}")
        }
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
