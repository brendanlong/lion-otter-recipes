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
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.Recipe
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for interacting with Firebase Firestore as the primary data store.
 *
 * Auth model:
 * - On startup, attempts anonymous auth (for offline-only local storage).
 *   If anonymous auth is not enabled in Firebase, the app works without auth
 *   until the user signs in with Google.
 * - When the user signs in with Google, the anonymous account (if any) is
 *   upgraded via linkWithCredential, or data is migrated if the Google account
 *   already exists.
 *
 * Network/sync model:
 * - Network is disabled by default (offline-only mode).
 * - Signing in with Google auto-enables sync (network enabled).
 * - The sync toggle lets users pause/resume sync while signed in.
 * - Sign-out disables network and clears auth state.
 *
 * Firestore data structure:
 *   users/{userId}/recipes/{recipeId} - Recipe documents (structured fields + originalHtml)
 *   users/{userId}/mealPlans/{mealPlanId} - Meal plan documents (structured fields)
 *
 * Documents are stored as structured Firestore fields (not JSON blobs), with
 * Instant timestamps converted to Firestore Timestamp objects for proper
 * date display and server-side ordering.
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
        private const val FIELD_ORIGINAL_HTML = "originalHtml"
    }

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val credentialManager: CredentialManager by lazy { CredentialManager.create(context) }

    private var snapshotListeners = mutableListOf<ListenerRegistration>()

    /**
     * Ensure the user is signed in (anonymously if not already signed in).
     * This should be called at app startup. Network is disabled by default
     * so the anonymous user operates purely offline until sync is enabled.
     */
    suspend fun ensureSignedIn() = withContext(Dispatchers.IO) {
        if (auth.currentUser == null) {
            // Disable network so the app operates offline until
            // the user signs in with Google and enables sync
            try {
                firestore.disableNetwork().await()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to disable network during ensureSignedIn", e)
            }
            try {
                auth.signInAnonymously().await()
                Log.d(TAG, "Signed in anonymously: ${auth.currentUser?.uid}")
            } catch (e: Exception) {
                Log.w(TAG, "Anonymous sign-in not available, app will work offline until Google sign-in", e)
            }
        }
    }

    /**
     * Sign in with Google using Credential Manager and Firebase Auth.
     * If the current user is anonymous, attempts to link the Google credential
     * to upgrade the anonymous account. If the Google account already exists
     * (credential already in use), migrates data from the anonymous account
     * to the existing Google account.
     *
     * @param filterByAuthorizedAccounts If true, only show accounts previously used to sign in.
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

                    val currentUser = auth.currentUser
                    if (currentUser != null && currentUser.isAnonymous) {
                        // Try to upgrade the anonymous account by linking
                        try {
                            currentUser.linkWithCredential(firebaseCredential).await()
                            Log.d(TAG, "Anonymous account upgraded to Google: ${currentUser.uid}")
                            return@withContext true
                        } catch (e: Exception) {
                            // Link failed — likely because the Google account already exists.
                            // Migrate data from anonymous to the existing Google account.
                            Log.w(TAG, "Link failed, migrating data to existing Google account", e)
                            val anonymousUid = currentUser.uid
                            val anonymousRecipes = readAllDocsForUser(anonymousUid, COLLECTION_RECIPES)
                            val anonymousMealPlans = readAllDocsForUser(anonymousUid, COLLECTION_MEAL_PLANS)

                            // Sign in with the Google account
                            auth.signInWithCredential(firebaseCredential).await()
                            val googleUid = auth.currentUser?.uid
                                ?: return@withContext false

                            // Copy data from anonymous user to Google user (only non-conflicting)
                            if (anonymousRecipes.isNotEmpty() || anonymousMealPlans.isNotEmpty()) {
                                migrateData(
                                    anonymousRecipes, COLLECTION_RECIPES,
                                    anonymousMealPlans, COLLECTION_MEAL_PLANS,
                                    googleUid
                                )
                                // Clean up anonymous user data
                                deleteUserData(anonymousUid)
                            }

                            Log.d(TAG, "Migrated data from anonymous ($anonymousUid) to Google ($googleUid)")
                            return@withContext true
                        }
                    } else {
                        // Not anonymous — just sign in normally
                        auth.signInWithCredential(firebaseCredential).await()
                        return@withContext auth.currentUser != null
                    }
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
     * Check if user is signed in with a Google account (not anonymous).
     */
    fun isSignedInWithGoogle(): Boolean {
        val user = auth.currentUser ?: return false
        return !user.isAnonymous
    }

    /**
     * Check if user is signed in (any auth, including anonymous).
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
     * Sign out from Google. Disables network access and clears auth state.
     * Uses timeouts to prevent hanging if Firestore operations are stuck.
     */
    suspend fun signOut() = withContext(NonCancellable + Dispatchers.IO) {
        removeAllListeners()
        try {
            withTimeoutOrNull(5_000L) {
                firestore.disableNetwork().await()
            } ?: Log.w(TAG, "Timed out disabling network during sign-out")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to disable network during sign-out", e)
        }
        auth.signOut()
        Log.d(TAG, "Signed out, currentUser: ${auth.currentUser?.uid}")
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear credential state during sign-out", e)
        }
    }

    /**
     * Enable Firestore network access (turns on sync).
     */
    suspend fun enableNetwork() = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(5_000L) {
                firestore.enableNetwork().await()
            } ?: Log.w(TAG, "Timed out enabling Firestore network")
            Log.d(TAG, "Firestore network enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable network", e)
        }
    }

    /**
     * Disable Firestore network access (turns off sync, offline-only).
     */
    suspend fun disableNetwork() = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(5_000L) {
                firestore.disableNetwork().await()
            } ?: Log.w(TAG, "Timed out disabling Firestore network")
            Log.d(TAG, "Firestore network disabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable network", e)
        }
    }

    private fun removeAllListeners() {
        snapshotListeners.forEach { it.remove() }
        snapshotListeners.clear()
    }

    private fun userRecipesCollection(userId: String? = null) =
        firestore.collection(COLLECTION_USERS)
            .document(userId ?: getUserId() ?: throw IllegalStateException("Not signed in"))
            .collection(COLLECTION_RECIPES)

    private fun userMealPlansCollection(userId: String? = null) =
        firestore.collection(COLLECTION_USERS)
            .document(userId ?: getUserId() ?: throw IllegalStateException("Not signed in"))
            .collection(COLLECTION_MEAL_PLANS)

    /**
     * Suspend until Firebase Auth has a current user, then return the UID.
     * Used by observe methods to avoid crashing if collected before ensureSignedIn() completes.
     * Times out after 10 seconds to prevent infinite hangs if auth never completes.
     */
    private suspend fun awaitUserId(): String {
        getUserId()?.let { return it }
        val uid = withTimeoutOrNull(10_000L) {
            suspendCancellableCoroutine { cont ->
                val listener = object : FirebaseAuth.AuthStateListener {
                    override fun onAuthStateChanged(firebaseAuth: FirebaseAuth) {
                        val uid = firebaseAuth.currentUser?.uid
                        if (uid != null) {
                            firebaseAuth.removeAuthStateListener(this)
                            cont.resume(uid)
                        }
                    }
                }
                auth.addAuthStateListener(listener)
                cont.invokeOnCancellation { auth.removeAuthStateListener(listener) }
            }
        }
        if (uid == null) {
            Log.e(TAG, "Timed out waiting for auth user ID")
            throw IllegalStateException("Not signed in: timed out waiting for auth")
        }
        return uid
    }

    private fun parseRemoteRecipe(data: Map<String, Any?>): RemoteRecipe {
        val originalHtml = (data[FIELD_ORIGINAL_HTML] as? String)?.ifEmpty { null }
        // Remove originalHtml before decoding since Recipe doesn't have that field
        val recipeData = data - FIELD_ORIGINAL_HTML
        val recipe = json.decodeFromFirestoreMap<Recipe>(recipeData)
        return RemoteRecipe(recipe, originalHtml)
    }

    private fun parseMealPlanEntry(data: Map<String, Any?>): MealPlanEntry {
        return json.decodeFromFirestoreMap<MealPlanEntry>(data)
    }

    // --- Recipe Operations ---

    /**
     * Upload or update a recipe in Firestore.
     */
    suspend fun upsertRecipe(recipe: Recipe, originalHtml: String?): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val data = json.encodeToFirestoreMap(recipe, RECIPE_TIMESTAMP_FIELDS).toMutableMap()
                data[FIELD_ORIGINAL_HTML] = originalHtml ?: ""
                userRecipesCollection().document(recipe.id).set(data).await()
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to upsert recipe ${recipe.name}", e)
                Result.failure(e)
            }
        }

    /**
     * Permanently delete a recipe document from Firestore.
     */
    suspend fun deleteRecipe(recipeId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                userRecipesCollection().document(recipeId).delete().await()
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete recipe: $recipeId", e)
                Result.failure(e)
            }
        }

    /**
     * Get a single recipe by ID (one-shot).
     */
    suspend fun getRecipeById(recipeId: String): RemoteRecipe? =
        withContext(Dispatchers.IO) {
            try {
                val doc = userRecipesCollection().document(recipeId).get().await()
                if (!doc.exists()) return@withContext null
                val data = doc.data ?: return@withContext null
                parseRemoteRecipe(data)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get recipe $recipeId", e)
                null
            }
        }

    /**
     * Get all recipes from Firestore (one-shot).
     */
    suspend fun getAllRecipes(): Result<List<RemoteRecipe>> =
        withContext(Dispatchers.IO) {
            try {
                val snapshot = userRecipesCollection().get().await()

                val recipes = snapshot.documents.mapNotNull { doc ->
                    try {
                        val data = doc.data ?: return@mapNotNull null
                        parseRemoteRecipe(data)
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
     * Observe all recipes in real-time via snapshot listener.
     * Returns a Flow that emits the full list on every change.
     */
    fun observeRecipes(): Flow<List<RemoteRecipe>> = callbackFlow {
        val userId = try {
            awaitUserId()
        } catch (e: Exception) {
            Log.w(TAG, "No auth user for observeRecipes, emitting empty list", e)
            trySend(emptyList())
            awaitClose {}
            return@callbackFlow
        }
        val registration = userRecipesCollection(userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Recipe snapshot listener error", error)
                    return@addSnapshotListener
                }

                val recipes = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        val data = doc.data ?: return@mapNotNull null
                        parseRemoteRecipe(data)
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

    /**
     * Observe a single recipe by ID in real-time.
     */
    fun observeRecipeById(recipeId: String): Flow<RemoteRecipe?> = callbackFlow {
        val userId = try {
            awaitUserId()
        } catch (e: Exception) {
            Log.w(TAG, "No auth user for observeRecipeById($recipeId)", e)
            trySend(null)
            awaitClose {}
            return@callbackFlow
        }
        val registration = userRecipesCollection(userId).document(recipeId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Recipe snapshot listener error for $recipeId", error)
                    return@addSnapshotListener
                }

                if (snapshot == null || !snapshot.exists()) {
                    trySend(null)
                    return@addSnapshotListener
                }

                try {
                    val data = snapshot.data
                    if (data != null) {
                        trySend(parseRemoteRecipe(data))
                    } else {
                        trySend(null)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse recipe from snapshot $recipeId", e)
                    trySend(null)
                }
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
                val data = json.encodeToFirestoreMap(entry, MEAL_PLAN_TIMESTAMP_FIELDS)
                userMealPlansCollection().document(entry.id).set(data).await()
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to upsert meal plan ${entry.id}", e)
                Result.failure(e)
            }
        }

    /**
     * Permanently delete a meal plan document from Firestore.
     */
    suspend fun deleteMealPlan(entryId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                userMealPlansCollection().document(entryId).delete().await()
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete meal plan: $entryId", e)
                Result.failure(e)
            }
        }

    /**
     * Get all meal plan entries from Firestore (one-shot).
     */
    suspend fun getAllMealPlans(): Result<List<MealPlanEntry>> =
        withContext(Dispatchers.IO) {
            try {
                val snapshot = userMealPlansCollection().get().await()

                val entries = snapshot.documents.mapNotNull { doc ->
                    try {
                        val data = doc.data ?: return@mapNotNull null
                        parseMealPlanEntry(data)
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

    /**
     * Observe all meal plans in real-time via snapshot listener.
     */
    fun observeMealPlans(): Flow<List<MealPlanEntry>> = callbackFlow {
        val userId = try {
            awaitUserId()
        } catch (e: Exception) {
            Log.w(TAG, "No auth user for observeMealPlans, emitting empty list", e)
            trySend(emptyList())
            awaitClose {}
            return@callbackFlow
        }
        val registration = userMealPlansCollection(userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Meal plan snapshot listener error", error)
                    return@addSnapshotListener
                }

                val entries = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        val data = doc.data ?: return@mapNotNull null
                        parseMealPlanEntry(data)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse meal plan from snapshot ${doc.id}", e)
                        null
                    }
                } ?: emptyList()

                trySend(entries)
            }

        snapshotListeners.add(registration)

        awaitClose {
            registration.remove()
            snapshotListeners.remove(registration)
        }
    }

    /**
     * Get a single meal plan entry by ID (one-shot).
     */
    suspend fun getMealPlanById(entryId: String): MealPlanEntry? =
        withContext(Dispatchers.IO) {
            try {
                val doc = userMealPlansCollection().document(entryId).get().await()
                if (!doc.exists()) return@withContext null
                val data = doc.data ?: return@withContext null
                parseMealPlanEntry(data)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get meal plan $entryId", e)
                null
            }
        }

    // --- Data Migration Helpers ---

    private suspend fun readAllDocsForUser(userId: String, collection: String): List<Pair<Map<String, Any?>, String>> {
        return try {
            val snapshot = firestore.collection(COLLECTION_USERS)
                .document(userId)
                .collection(collection)
                .get().await()
            snapshot.documents.map { doc -> Pair(doc.data ?: emptyMap(), doc.id) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read $collection for user $userId", e)
            emptyList()
        }
    }

    private suspend fun migrateData(
        recipes: List<Pair<Map<String, Any?>, String>>,
        recipesCollection: String,
        mealPlans: List<Pair<Map<String, Any?>, String>>,
        mealPlansCollection: String,
        targetUserId: String
    ) {
        // Check which docs already exist in the target to avoid overwriting
        val existingRecipeIds = try {
            firestore.collection(COLLECTION_USERS)
                .document(targetUserId)
                .collection(recipesCollection)
                .get().await()
                .documents.map { it.id }.toSet()
        } catch (_: Exception) { emptySet() }

        val existingMealPlanIds = try {
            firestore.collection(COLLECTION_USERS)
                .document(targetUserId)
                .collection(mealPlansCollection)
                .get().await()
                .documents.map { it.id }.toSet()
        } catch (_: Exception) { emptySet() }

        val batch = firestore.batch()

        for ((data, docId) in recipes) {
            if (docId !in existingRecipeIds) {
                val docRef = firestore.collection(COLLECTION_USERS)
                    .document(targetUserId)
                    .collection(recipesCollection)
                    .document(docId)
                batch.set(docRef, data)
            }
        }

        for ((data, docId) in mealPlans) {
            if (docId !in existingMealPlanIds) {
                val docRef = firestore.collection(COLLECTION_USERS)
                    .document(targetUserId)
                    .collection(mealPlansCollection)
                    .document(docId)
                batch.set(docRef, data)
            }
        }

        try {
            batch.commit().await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to migrate data to user $targetUserId", e)
        }
    }

    private suspend fun deleteUserData(userId: String) {
        try {
            val recipeSnapshot = firestore.collection(COLLECTION_USERS)
                .document(userId)
                .collection(COLLECTION_RECIPES)
                .get().await()
            val batch = firestore.batch()
            for (doc in recipeSnapshot.documents) {
                batch.delete(doc.reference)
            }

            val mealPlanSnapshot = firestore.collection(COLLECTION_USERS)
                .document(userId)
                .collection(COLLECTION_MEAL_PLANS)
                .get().await()
            for (doc in mealPlanSnapshot.documents) {
                batch.delete(doc.reference)
            }

            batch.commit().await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete data for user $userId", e)
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
