package com.lionotter.recipes.testutil

import com.google.firebase.Firebase
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.firestore
import com.lionotter.recipes.data.remote.FirestoreService

/**
 * Test-only subclass of FirestoreService that bypasses Firebase Auth.
 * Uses a fixed user path ("users/test-user/...") so tests don't need
 * a signed-in user.
 */
class TestFirestoreService : FirestoreService() {

    companion object {
        private const val TEST_USER_ID = "test-user"
    }

    override fun recipesCollection(): CollectionReference {
        return Firebase.firestore.collection("users").document(TEST_USER_ID).collection("recipes")
    }

    override fun mealPlansCollection(): CollectionReference {
        return Firebase.firestore.collection("users").document(TEST_USER_ID).collection("mealPlans")
    }
}
