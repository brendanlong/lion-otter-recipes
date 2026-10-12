package com.lionotter.recipes.data.remote

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.firestore
import com.google.firebase.firestore.firestoreSettings
import com.google.firebase.firestore.persistentCacheSettings
import com.lionotter.recipes.util.ErrorLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class FirestoreService @Inject constructor() {

    companion object {
        private const val TAG = "FirestoreService"
        const val USERS_COLLECTION = "users"
        const val RECIPES_COLLECTION = "recipes"
        const val MEAL_PLANS_COLLECTION = "mealPlans"
        private const val CLEAR_PERSISTENCE_ATTEMPTS = 3
        private const val CLEAR_PERSISTENCE_RETRY_DELAY_MS = 250L
    }

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val _isNetworkEnabled = MutableStateFlow(true)
    val isNetworkEnabled: StateFlow<Boolean> = _isNetworkEnabled.asStateFlow()

    /**
     * Incremented each time the Firestore instance is recycled (terminate + clearPersistence).
     * Repositories combine this with [AuthState] so that their snapshot listeners
     * are automatically re-created after a Firestore reset.
     */
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    init {
        configureInstance()
        // Guest data lives only as never-synced local writes. The network must be
        // disabled before anything else touches Firestore, otherwise a cold start
        // (e.g. a relaunched WorkManager job) could push guest writes to the server,
        // where they are rejected and rolled back locally.
        if (!hasSignedInGoogleUser()) {
            startDisablingNetwork()
        }
    }

    /** Returns the collection for the given user's recipes. */
    open fun recipesCollection(uid: String): CollectionReference {
        return Firebase.firestore.collection(USERS_COLLECTION).document(uid).collection(RECIPES_COLLECTION)
    }

    /** Returns the collection for the given user's meal plans. */
    open fun mealPlansCollection(uid: String): CollectionReference {
        return Firebase.firestore.collection(USERS_COLLECTION).document(uid).collection(MEAL_PLANS_COLLECTION)
    }

    fun reportError(message: String) {
        ErrorLog.e(TAG, message)
        _errors.tryEmit(message)
    }

    /** Disable Firestore network access (offline-only mode for guest users). */
    suspend fun disableNetwork() {
        try {
            Firebase.firestore.disableNetwork().await()
            _isNetworkEnabled.value = false
            Log.d(TAG, "Firestore network disabled")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Failed to disable Firestore network", e)
        }
    }

    /** Enable Firestore network access (when user signs in with Google). */
    suspend fun enableNetwork() {
        try {
            Firebase.firestore.enableNetwork().await()
            _isNetworkEnabled.value = true
            Log.d(TAG, "Firestore network enabled")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Failed to enable Firestore network", e)
        }
    }

    /**
     * Terminates Firestore and clears the local persistence cache, then configures the
     * fresh instance. The [generation] is bumped even on failure so listeners always
     * re-attach to the live instance.
     *
     * Clearing fails if something uses Firestore between terminate and clear, so it is
     * retried a few times. If it still fails, false is returned so the caller can tell the user.
     *
     * @param enableNetwork whether the fresh instance should be online (Google user)
     *        or offline (guest).
     * @return true if the local cache was cleared
     */
    suspend fun clearLocalData(enableNetwork: Boolean): Boolean {
        var cleared = false
        try {
            for (attempt in 1..CLEAR_PERSISTENCE_ATTEMPTS) {
                try {
                    val old = Firebase.firestore
                    old.terminate().await()
                    old.clearPersistence().await()
                    cleared = true
                    break
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    ErrorLog.e(TAG, "Error clearing Firestore persistence (attempt $attempt)", e)
                    if (attempt < CLEAR_PERSISTENCE_ATTEMPTS) delay(CLEAR_PERSISTENCE_RETRY_DELAY_MS)
                }
            }
        } finally {
            configureInstance()
            if (!enableNetwork) startDisablingNetwork()
            _generation.value++
        }
        if (enableNetwork) enableNetwork()
        return cleared
    }

    /**
     * Waits for all pending local writes to be acknowledged by the server.
     * @return true if all writes were acknowledged within [timeoutMs].
     */
    suspend fun awaitPendingWrites(timeoutMs: Long): Boolean {
        return try {
            withTimeoutOrNull(timeoutMs) {
                Firebase.firestore.waitForPendingWrites().await()
                true
            } ?: false
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "waitForPendingWrites failed", e)
            false
        }
    }

    private fun hasSignedInGoogleUser(): Boolean = try {
        val user = Firebase.auth.currentUser
        user != null && !user.isAnonymous
    } catch (e: Exception) {
        Log.w(TAG, "Could not read Firebase Auth user; assuming guest", e)
        false
    }

    /** Applies cache settings to the current Firestore instance. Must run before its first use. */
    private fun configureInstance() {
        try {
            val settings = firestoreSettings {
                setLocalCacheSettings(persistentCacheSettings {
                    setSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                })
            }
            Firebase.firestore.firestoreSettings = settings
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Firestore settings already configured: ${e.message}")
        }

        try {
            Firebase.firestore.persistentCacheIndexManager?.enableIndexAutoCreation()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enable index auto-creation", e)
        }
    }

    /** Enqueues disableNetwork synchronously so it is ordered before any later Firestore call. */
    private fun startDisablingNetwork() {
        _isNetworkEnabled.value = false
        try {
            Firebase.firestore.disableNetwork()
                .addOnSuccessListener { Log.d(TAG, "Firestore network disabled") }
                .addOnFailureListener { e -> ErrorLog.e(TAG, "Failed to disable Firestore network", e) }
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Failed to disable Firestore network", e)
        }
    }
}
