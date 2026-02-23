package com.lionotter.recipes.data.remote

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.firestore
import com.google.firebase.firestore.firestoreSettings
import com.google.firebase.firestore.persistentCacheSettings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class FirestoreService @Inject constructor() {

    companion object {
        private const val TAG = "FirestoreService"
        const val USERS_COLLECTION = "users"
        const val RECIPES_COLLECTION = "recipes"
        const val MEAL_PLANS_COLLECTION = "mealPlans"
    }

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val _isNetworkEnabled = MutableStateFlow(true)
    val isNetworkEnabled: StateFlow<Boolean> = _isNetworkEnabled.asStateFlow()

    /**
     * Incremented each time the Firestore instance is recycled (terminate + clearPersistence).
     * Repositories combine this with the current user ID so that their snapshot listeners
     * are automatically re-created after a Firestore reset — no manual re-subscription needed.
     */
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    init {
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

        Firebase.firestore.persistentCacheIndexManager?.let { indexManager ->
            indexManager.enableIndexAutoCreation()
        }
    }

    private fun requireUid(): String {
        return Firebase.auth.currentUser?.uid
            ?: throw IllegalStateException("User not authenticated")
    }

    open fun recipesCollection(): CollectionReference {
        val uid = requireUid()
        return Firebase.firestore.collection(USERS_COLLECTION).document(uid).collection(RECIPES_COLLECTION)
    }

    open fun mealPlansCollection(): CollectionReference {
        val uid = requireUid()
        return Firebase.firestore.collection(USERS_COLLECTION).document(uid).collection(MEAL_PLANS_COLLECTION)
    }

    fun reportError(message: String) {
        Log.e(TAG, message)
        _errors.tryEmit(message)
    }

    /** Disable Firestore network access (offline-only mode for anonymous users). */
    suspend fun disableNetwork() {
        try {
            Firebase.firestore.disableNetwork().await()
            _isNetworkEnabled.value = false
            Log.d(TAG, "Firestore network disabled")
        } catch (e: Exception) {
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
            Log.w(TAG, "Failed to enable Firestore network", e)
        }
    }

    /** Terminates Firestore and clears the local persistence cache. */
    suspend fun clearLocalData() {
        try {
            Firebase.firestore.terminate().await()
            Firebase.firestore.clearPersistence().await()
            _generation.value++
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing Firestore persistence", e)
        }
    }
}
