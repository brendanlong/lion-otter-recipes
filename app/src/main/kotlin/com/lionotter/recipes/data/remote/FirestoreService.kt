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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestoreService @Inject constructor() {

    companion object {
        private const val TAG = "FirestoreService"
    }

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    init {
        val settings = firestoreSettings {
            setLocalCacheSettings(persistentCacheSettings {
                setSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
            })
        }
        Firebase.firestore.firestoreSettings = settings

        Firebase.firestore.persistentCacheIndexManager?.let { indexManager ->
            indexManager.enableIndexAutoCreation()
            Log.d(TAG, "Firestore auto index creation enabled")
        }
    }

    private fun requireUid(): String {
        return Firebase.auth.currentUser?.uid
            ?: throw IllegalStateException("User not authenticated")
    }

    fun recipesCollection(): CollectionReference {
        val uid = requireUid()
        return Firebase.firestore.collection("users").document(uid).collection("recipes")
    }

    fun mealPlansCollection(): CollectionReference {
        val uid = requireUid()
        return Firebase.firestore.collection("users").document(uid).collection("mealPlans")
    }

    fun recipeContentCollection(recipeId: String): CollectionReference {
        val uid = requireUid()
        return Firebase.firestore.collection("users").document(uid)
            .collection("recipes").document(recipeId).collection("content")
    }

    fun reportError(message: String) {
        Log.e(TAG, message)
        _errors.tryEmit(message)
    }

    suspend fun signOut() {
        try {
            Firebase.firestore.terminate().await()
            Firebase.firestore.clearPersistence().await()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing Firestore persistence", e)
        }
        Firebase.auth.signOut()
    }
}
