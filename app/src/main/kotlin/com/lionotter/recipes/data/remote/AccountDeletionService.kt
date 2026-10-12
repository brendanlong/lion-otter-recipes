package com.lionotter.recipes.data.remote

import android.content.Context
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.firestore
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.repository.ImportDebugRepository
import com.lionotter.recipes.util.ErrorLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles complete deletion of a user's account and all associated data.
 *
 * Deletes (in order):
 * 1. Verifies the server is reachable and re-authenticates if needed (nothing deleted yet)
 * 2. Reads all recipe and meal plan documents from the server
 * 3. Deletes all recipes and meal plans from Firestore
 * 4. Deletes each recipe image from Firebase Storage individually (failures are
 *    logged and leave orphaned images rather than recipes pointing at deleted images)
 * 5. Deletes the user document
 * 6. Clears local image cache, import debug data and the API key
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
    private val imageSyncService: ImageSyncService,
    private val importDebugRepository: ImportDebugRepository,
    private val settingsDataStore: SettingsDataStore
) {
    companion object {
        private const val TAG = "AccountDeletionService"
        private const val SERVER_TIMEOUT_MS = 30_000L
        private const val BATCH_LIMIT = 500

        /** Firebase requires a sign-in within roughly this window to delete an account. */
        private const val RECENT_LOGIN_WINDOW_MS = 5 * 60 * 1000L
    }

    /** The server could not be reached. Nothing was deleted. */
    class OfflineException(cause: Throwable? = null) :
        Exception("You need to be online to delete your account", cause)

    /** All user data was deleted but the Firebase Auth account itself could not be. */
    class AuthAccountDeletionException(cause: Throwable) :
        Exception("Your data was deleted, but the account could not be removed", cause)

    /**
     * Deletes all user data from Firebase and the local device, then deletes
     * the Firebase Auth account.
     *
     * @param reauthCredential A fresh Google credential used to re-authenticate before
     *        deleting anything. If null, the last sign-in must be recent.
     * @throws ReauthenticationRequiredException before deleting anything if a fresh
     *         login is required and no usable credential was supplied
     * @throws OfflineException before deleting anything if the server is unreachable
     * @throws AuthAccountDeletionException if data was deleted but the auth account wasn't
     */
    suspend fun deleteAllUserData(reauthCredential: AuthCredential? = null) {
        val user = Firebase.auth.currentUser
            ?: throw IllegalStateException("No authenticated user")
        val uid = user.uid

        Log.i(TAG, "Starting account deletion for user $uid")

        reauthenticate(user, reauthCredential)

        // Reading from the server both verifies connectivity and gives the full,
        // authoritative set of documents to delete (not just what is cached).
        val recipes = readFromServer(firestoreService.recipesCollection(uid))
        val mealPlans = readFromServer(firestoreService.mealPlansCollection(uid))

        val imageUrls = recipes.mapNotNull { doc ->
            doc.getString("imageUrl")?.takeIf { imageSyncService.isStoragePath(it) }
        }.distinct()

        // Documents first: if a batch fails, the remaining recipes still have their images.
        deleteDocuments(recipes)
        deleteDocuments(mealPlans)
        deleteStorageImages(imageUrls)
        try {
            serverCall {
                Firebase.firestore.collection(FirestoreService.USERS_COLLECTION)
                    .document(uid)
                    .delete()
                    .await()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Failed to delete user document (may not exist)", e)
        }

        clearLocalUserData()

        try {
            serverCall { user.delete().await() }
            Log.i(TAG, "Firebase Auth account deleted")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw AuthAccountDeletionException(e)
        }

        Log.i(TAG, "Account deletion completed for user $uid")
    }

    private suspend fun reauthenticate(user: FirebaseUser, credential: AuthCredential?) {
        if (credential != null) {
            try {
                serverCall { user.reauthenticate(credential).await() }
                return
            } catch (e: OfflineException) {
                throw e
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                throw ReauthenticationRequiredException(e)
            }
        }
        val lastSignIn = user.metadata?.lastSignInTimestamp ?: 0L
        if (System.currentTimeMillis() - lastSignIn > RECENT_LOGIN_WINDOW_MS) {
            throw ReauthenticationRequiredException()
        }
    }

    private suspend fun readFromServer(collection: CollectionReference): List<DocumentSnapshot> {
        return try {
            serverCall { collection.get(Source.SERVER).await().documents }
        } catch (e: OfflineException) {
            throw e
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ErrorLog.e(TAG, "Failed to read ${collection.path} from server", e)
            throw OfflineException(e)
        }
    }

    /** Deletes documents using batched writes (max [BATCH_LIMIT] operations each). */
    private suspend fun deleteDocuments(documents: List<DocumentSnapshot>) {
        if (documents.isEmpty()) return
        for (chunk in documents.chunked(BATCH_LIMIT)) {
            val batch = Firebase.firestore.batch()
            for (doc in chunk) {
                batch.delete(doc.reference)
            }
            serverCall { batch.commit().await() }
        }
        Log.d(TAG, "Deleted ${documents.size} documents")
    }

    /** Runs a server round trip, converting a timeout into [OfflineException]. */
    private suspend fun <T> serverCall(block: suspend () -> T): T {
        return try {
            withTimeout(SERVER_TIMEOUT_MS) { block() }
        } catch (e: TimeoutCancellationException) {
            throw OfflineException(e)
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
                if (e is CancellationException) throw e
                Log.w(TAG, "Failed to delete storage image: $url", e)
            }
        }
        Log.d(TAG, "Deleted $deletedCount/${imageUrls.size} images from Firebase Storage")
    }

    private suspend fun clearLocalUserData() {
        ImageCacheConfig.clearCache(context)
        try {
            importDebugRepository.deleteAllDebugEntries()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Failed to clear import debug data", e)
        }
        try {
            settingsDataStore.clearAnthropicApiKey()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Failed to clear API key", e)
        }
    }
}
