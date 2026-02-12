package com.lionotter.recipes.data.remote

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.Recipe
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for interacting with Firebase Firestore for recipe and meal plan sync.
 * Uses Firebase Auth with Google Sign-In via Credential Manager for authentication.
 *
 * Firestore data structure:
 *   users/{userId}/recipes/{recipeId} - Recipe documents
 *   users/{userId}/mealPlans/{mealPlanId} - Meal plan documents
 *   users/{userId}/metadata/sync - Last sync metadata
 */
@Singleton
class FirestoreService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json
) {
    companion object {
        private const val TAG = "FirestoreService"
        private const val COLLECTION_USERS = "users"
        private const val COLLECTION_RECIPES = "recipes"
        private const val COLLECTION_MEAL_PLANS = "mealPlans"
        private const val FIELD_RECIPE_JSON = "recipeJson"
        private const val FIELD_ORIGINAL_HTML = "originalHtml"
        private const val FIELD_UPDATED_AT = "updatedAt"
        private const val FIELD_DELETED = "deleted"
        private const val FIELD_MEAL_PLAN_JSON = "mealPlanJson"
    }

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val credentialManager: CredentialManager by lazy { CredentialManager.create(context) }

    private var snapshotListeners = mutableListOf<ListenerRegistration>()

    /**
     * Sign in with Google using Credential Manager and Firebase Auth.
     * Uses GetGoogleIdOption to get a Google ID token, then authenticates with Firebase.
     *
     * @param filterByAuthorizedAccounts If true, only show accounts previously used to sign in.
     *   Set to false to show all Google accounts on the device.
     * @return true if sign-in was successful
     */
    suspend fun signInWithGoogle(filterByAuthorizedAccounts: Boolean = true): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val googleIdOption = GetGoogleIdOption.Builder()
                    .setServerClientId(context.getString(R.string.default_web_client_id))
                    .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
                    .build()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()

                val result = credentialManager.getCredential(context, request)
                val credential = result.credential

                if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    val firebaseCredential = GoogleAuthProvider.getCredential(
                        googleIdTokenCredential.idToken, null
                    )
                    auth.signInWithCredential(firebaseCredential).await()
                    auth.currentUser != null
                } else {
                    Log.w(TAG, "Unexpected credential type: ${credential.type}")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Google sign-in failed", e)
                throw e
            }
        }

    /**
     * Check if user is signed in to Firebase.
     */
    fun isSignedIn(): Boolean {
        return auth.currentUser != null
    }

    /**
     * Get the current user ID.
     */
    fun getUserId(): String? {
        return auth.currentUser?.uid
    }

    /**
     * Sign out from Firebase and Google.
     */
    suspend fun signOut() {
        withContext(Dispatchers.IO) {
            removeAllListeners()
            auth.signOut()
            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (_: Exception) {
                // Best-effort clear
            }
        }
    }

    private fun removeAllListeners() {
        snapshotListeners.forEach { it.remove() }
        snapshotListeners.clear()
    }

    private fun userRecipesCollection() =
        firestore.collection(COLLECTION_USERS)
            .document(getUserId() ?: throw IllegalStateException("Not signed in"))
            .collection(COLLECTION_RECIPES)

    private fun userMealPlansCollection() =
        firestore.collection(COLLECTION_USERS)
            .document(getUserId() ?: throw IllegalStateException("Not signed in"))
            .collection(COLLECTION_MEAL_PLANS)

    // --- Recipe Operations ---

    /**
     * Upload or update a recipe in Firestore.
     */
    suspend fun upsertRecipe(recipe: Recipe, originalHtml: String?): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val data = hashMapOf(
                    FIELD_RECIPE_JSON to json.encodeToString(recipe),
                    FIELD_ORIGINAL_HTML to (originalHtml ?: ""),
                    FIELD_UPDATED_AT to recipe.updatedAt.toEpochMilliseconds(),
                    FIELD_DELETED to false
                )
                userRecipesCollection().document(recipe.id).set(data).await()
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to upsert recipe ${recipe.name}", e)
                Result.failure(e)
            }
        }

    /**
     * Mark a recipe as deleted in Firestore (soft-delete).
     */
    suspend fun markRecipeDeleted(recipeId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                userRecipesCollection().document(recipeId)
                    .set(
                        hashMapOf(
                            FIELD_DELETED to true,
                            FIELD_UPDATED_AT to System.currentTimeMillis()
                        ),
                        SetOptions.merge()
                    ).await()
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to mark recipe deleted: $recipeId", e)
                Result.failure(e)
            }
        }

    /**
     * Permanently delete a recipe document from Firestore.
     */
    suspend fun hardDeleteRecipe(recipeId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                userRecipesCollection().document(recipeId).delete().await()
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to hard delete recipe: $recipeId", e)
                Result.failure(e)
            }
        }

    /**
     * Get all recipes from Firestore (one-shot).
     * Returns pairs of (Recipe, originalHtml).
     */
    suspend fun getAllRecipes(): Result<List<RemoteRecipe>> =
        withContext(Dispatchers.IO) {
            try {
                val snapshot = userRecipesCollection()
                    .whereEqualTo(FIELD_DELETED, false)
                    .get().await()

                val recipes = snapshot.documents.mapNotNull { doc ->
                    try {
                        val recipeJson = doc.getString(FIELD_RECIPE_JSON) ?: return@mapNotNull null
                        val recipe = json.decodeFromString<Recipe>(recipeJson)
                        val originalHtml = doc.getString(FIELD_ORIGINAL_HTML)?.ifEmpty { null }
                        RemoteRecipe(recipe, originalHtml)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse recipe from document ${doc.id}", e)
                        null
                    }
                }
                Result.success(recipes)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get all recipes", e)
                Result.failure(e)
            }
        }

    /**
     * Observe recipe changes in real-time via snapshot listener.
     */
    fun observeRecipeChanges(): Flow<List<RemoteRecipe>> = callbackFlow {
        val registration = userRecipesCollection()
            .whereEqualTo(FIELD_DELETED, false)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Recipe snapshot listener error", error)
                    return@addSnapshotListener
                }

                val recipes = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        val recipeJson = doc.getString(FIELD_RECIPE_JSON) ?: return@mapNotNull null
                        val recipe = json.decodeFromString<Recipe>(recipeJson)
                        val originalHtml = doc.getString(FIELD_ORIGINAL_HTML)?.ifEmpty { null }
                        RemoteRecipe(recipe, originalHtml)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse recipe from snapshot ${doc.id}", e)
                        null
                    }
                } ?: emptyList()

                trySend(recipes)
            }

        snapshotListeners.add(registration)

        awaitClose {
            registration.remove()
            snapshotListeners.remove(registration)
        }
    }

    // --- Meal Plan Operations ---

    /**
     * Upload or update a meal plan entry in Firestore.
     */
    suspend fun upsertMealPlan(entry: MealPlanEntry): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val data = hashMapOf(
                    FIELD_MEAL_PLAN_JSON to json.encodeToString(entry),
                    FIELD_UPDATED_AT to entry.updatedAt.toEpochMilliseconds(),
                    FIELD_DELETED to false
                )
                userMealPlansCollection().document(entry.id).set(data).await()
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to upsert meal plan ${entry.id}", e)
                Result.failure(e)
            }
        }

    /**
     * Mark a meal plan entry as deleted in Firestore.
     */
    suspend fun markMealPlanDeleted(entryId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                userMealPlansCollection().document(entryId)
                    .set(
                        hashMapOf(
                            FIELD_DELETED to true,
                            FIELD_UPDATED_AT to System.currentTimeMillis()
                        ),
                        SetOptions.merge()
                    ).await()
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to mark meal plan deleted: $entryId", e)
                Result.failure(e)
            }
        }

    /**
     * Permanently delete a meal plan document from Firestore.
     */
    suspend fun hardDeleteMealPlan(entryId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                userMealPlansCollection().document(entryId).delete().await()
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to hard delete meal plan: $entryId", e)
                Result.failure(e)
            }
        }

    /**
     * Get all meal plan entries from Firestore (one-shot).
     */
    suspend fun getAllMealPlans(): Result<List<MealPlanEntry>> =
        withContext(Dispatchers.IO) {
            try {
                val snapshot = userMealPlansCollection()
                    .whereEqualTo(FIELD_DELETED, false)
                    .get().await()

                val entries = snapshot.documents.mapNotNull { doc ->
                    try {
                        val entryJson = doc.getString(FIELD_MEAL_PLAN_JSON)
                            ?: return@mapNotNull null
                        json.decodeFromString<MealPlanEntry>(entryJson)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse meal plan from document ${doc.id}", e)
                        null
                    }
                }
                Result.success(entries)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get all meal plans", e)
                Result.failure(e)
            }
        }
}

/**
 * Represents a recipe fetched from Firestore, including its original HTML.
 */
data class RemoteRecipe(
    val recipe: Recipe,
    val originalHtml: String?
)
