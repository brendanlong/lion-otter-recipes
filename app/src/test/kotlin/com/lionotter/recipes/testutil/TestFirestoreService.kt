package com.lionotter.recipes.testutil

import com.google.firebase.Firebase
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.firestore
import com.lionotter.recipes.data.remote.FirestoreService

/**
 * Test-only subclass of FirestoreService that bypasses Firebase Auth.
 * Ignores the [uid] parameter and always uses "test-user" so tests
 * don't need a signed-in user.
 */
class TestFirestoreService : FirestoreService() {

    companion object {
        private const val TEST_USER_ID = "test-user"
    }

    override fun recipesCollection(uid: String): CollectionReference {
        return Firebase.firestore.collection("users").document(TEST_USER_ID).collection("recipes")
    }

    override fun mealPlansCollection(uid: String): CollectionReference {
        return Firebase.firestore.collection("users").document(TEST_USER_ID).collection("mealPlans")
    }
}
