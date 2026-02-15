package com.lionotter.recipes.data.remote

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.firestore
import com.google.firebase.firestore.firestoreSettings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestoreService @Inject constructor(
    private val authService: AuthService
) {
    companion object {
        private const val TAG = "FirestoreService"
    }

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private var configured = false

    private val db: FirebaseFirestore
        get() {
            val firestore = Firebase.firestore
            if (!configured) {
                try {
                    firestore.firestoreSettings = firestoreSettings {
                        setLocalCacheSettings(PersistentCacheSettings.newBuilder()
                            .setSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                            .build())
                    }
                    firestore.persistentCacheIndexManager?.enableIndexAutoCreation()
                } catch (e: Exception) {
                    // Settings can only be set once; ignore if already configured
                    Log.w(TAG, "Firestore settings already configured", e)
                }
                configured = true
            }
            return firestore
        }

    fun reportError(message: String) {
        Log.e(TAG, message)
        _errors.tryEmit(message)
    }

    fun recipesCollection(): CollectionReference {
        val uid = authService.currentUserId
            ?: throw IllegalStateException("Must be signed in to access recipes")
        return db.collection("users").document(uid).collection("recipes")
    }

    fun mealPlansCollection(): CollectionReference {
        val uid = authService.currentUserId
            ?: throw IllegalStateException("Must be signed in to access meal plans")
        return db.collection("users").document(uid).collection("mealPlans")
    }
}
