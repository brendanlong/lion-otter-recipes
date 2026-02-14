package com.lionotter.recipes.data.remote

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.model.Amount
import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.InstructionStep
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.MealType
import com.lionotter.recipes.domain.model.Recipe
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firestore-first storage service. All recipe and meal plan data lives in Firestore,
 * with offline persistence handled by the Firestore SDK's built-in cache.
 *
 * Authentication flow:
 * - On startup, uses a locally-generated UUID as user ID with network disabled (local-only mode)
 * - No Firebase Auth is used until the user signs in with Google
 * - Users can sign in with Google Auth, which enables network access for cross-device sync
 * - Signing out copies data to a new local user, then signs out of Google
 * - Local-only users always have network disabled
 *
 * Data structure:
 *   users/{userId}/recipes/{recipeId} - Recipe documents
 *   users/{userId}/mealPlans/{mealPlanId} - Meal plan documents
 */
@Singleton
class FirestoreService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "FirestoreService"
        private const val COLLECTION_USERS = "users"
        private const val COLLECTION_RECIPES = "recipes"
        private const val COLLECTION_MEAL_PLANS = "mealPlans"
        private const val FIELD_UPDATED_AT = "updatedAt"
        private const val PREFS_NAME = "firestore_user"
        private const val KEY_LOCAL_USER_ID = "local_user_id"
        private const val GET_TIMEOUT_MS = 10_000L
    }

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private var firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
    private val credentialManager: CredentialManager by lazy { CredentialManager.create(context) }

    /**
     * Whether the current user is signed in with Google (not anonymous).
     */
    private val _isGoogleSignedIn = MutableStateFlow(false)
    val isGoogleSignedIn: StateFlow<Boolean> = _isGoogleSignedIn.asStateFlow()

    /**
     * Current user ID. Always non-null after [ensureUser] has been called.
     */
    private val _currentUserId = MutableStateFlow<String?>(null)
    val currentUserId: StateFlow<String?> = _currentUserId.asStateFlow()

    /**
     * Whether Firestore network access is currently enabled.
     */
    private val _networkEnabled = MutableStateFlow(false)
    val networkEnabled: StateFlow<Boolean> = _networkEnabled.asStateFlow()

    /**
     * Non-null when [ensureUser] failed and the data layer is non-functional.
     * The UI should observe this and display the error to the user.
     */
    private val _initializationError = MutableStateFlow<String?>(null)
    val initializationError: StateFlow<String?> = _initializationError.asStateFlow()

    /**
     * Errors from snapshot listeners that the UI should display.
     * Emitted when a listener encounters a non-recoverable error (e.g. permission
     * denied). The listener is dead after this — [flatMapLatest] keeps the outer
     * flow alive so a new [_currentUserId] emission can restart it.
     */
    private val _listenerErrors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val listenerErrors: SharedFlow<String> = _listenerErrors.asSharedFlow()

    /**
     * Called by the application when initialization fails.
     * Sets the error message that the UI should display.
     */
    fun setInitializationError(message: String) {
        _initializationError.value = message
    }

    /**
     * Ensures a user ID is available for Firestore operations.
     * If signed in with Google, uses the Firebase Auth UID.
     * Otherwise, uses a locally-generated UUID (no Firebase Auth needed).
     *
     * IMPORTANT: Network state is always established and awaited *before* setting
     * [_currentUserId], because setting the user ID triggers [flatMapLatest] to
     * register snapshot listeners. If the network state hasn't been applied yet,
     * listeners may try to contact the server for a local-only user (which will
     * hang) or read from an empty cache for a Google user (missing data).
     *
     * @param syncEnabled For Google-signed-in users, whether sync (network access)
     *   is enabled. Ignored for local-only users (network is always disabled).
     */
    suspend fun ensureUser(syncEnabled: Boolean = false) {
        withContext(Dispatchers.IO) {
            val currentUser = auth.currentUser
            if (currentUser != null && !currentUser.isAnonymous) {
                // Set network state BEFORE userId to avoid listeners firing
                // with the wrong source. Awaiting ensures the Firestore SDK
                // has completed the transition before we register listeners.
                _isGoogleSignedIn.value = true
                if (syncEnabled) {
                    enableNetwork()
                } else {
                    disableNetwork()
                }
                _currentUserId.value = currentUser.uid
                Log.d(TAG, "ensureUser: Google user ${currentUser.uid} (sync=$syncEnabled)")
            } else {
                // No Google user — disable network and use a local-only user ID.
                // No Firebase Auth needed; Firestore offline cache works with any path.
                _isGoogleSignedIn.value = false
                disableNetwork()
                val localId = getOrCreateLocalUserId()
                _currentUserId.value = localId
                Log.d(TAG, "ensureUser: local user $localId")
            }
        }
    }

    /**
     * Get the persisted local user ID, or create one if it doesn't exist.
     * This ID is stable across app restarts so the same Firestore cache path is used.
     */
    private fun getOrCreateLocalUserId(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_LOCAL_USER_ID, null)
        if (existing != null) return existing

        val newId = "local_${java.util.UUID.randomUUID()}"
        prefs.edit {putString(KEY_LOCAL_USER_ID, newId)}
        return newId
    }

    /**
     * Enable Firestore network access. Called when sync is enabled for Google-signed-in users.
     * Suspends until the Firestore SDK has completed the network state transition,
     * ensuring snapshot listeners registered afterward will use the correct source.
     */
    suspend fun enableNetwork() {
        _networkEnabled.value = true
        firestore.enableNetwork().await()
        Log.d(TAG, "Network enabled")
    }

    /**
     * Disable Firestore network access. Called for anonymous users and when sync is disabled.
     * Suspends until the Firestore SDK has completed the network state transition,
     * ensuring snapshot listeners registered afterward will read from cache only.
     */
    suspend fun disableNetwork() {
        _networkEnabled.value = false
        firestore.disableNetwork().await()
        Log.d(TAG, "Network disabled")
    }

    /**
     * Terminate the current Firestore instance and clear its persistence cache
     * (including any pending writes), then obtain a fresh instance.
     *
     * This MUST be called when switching users (sign-in / sign-out) because
     * pending writes queued under the old user's path (e.g. users/local_xxx/…)
     * will be rejected by security rules once the authenticated UID changes.
     * Those permanently-rejected writes block the Firestore gRPC stream,
     * preventing all subsequent snapshot listener updates from being delivered.
     */
    private suspend fun resetFirestore() {
        firestore.terminate().await()
        firestore.clearPersistence().await()
        firestore = FirebaseFirestore.getInstance()
        Log.d(TAG, "Firestore instance reset (terminated + cleared persistence)")
    }

    /**
     * Sign in with Google using Credential Manager and Firebase Auth.
     * Copies local data to the Google account so the user doesn't lose existing recipes.
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

                    val localUserId = _currentUserId.value

                    // Sign in with Google via Firebase Auth
                    auth.signInWithCredential(firebaseCredential).await()
                    val googleUser = auth.currentUser
                    if (googleUser != null) {
                        // Copy data from local user to Google user
                        if (localUserId != null && localUserId != googleUser.uid) {
                            copyUserData(localUserId, googleUser.uid)
                        }
                        // Reset Firestore to clear pending writes queued under the
                        // old local_* user path. Those writes would be rejected by
                        // security rules (auth.uid != "local_*") and block the
                        // gRPC stream, hanging all subsequent reads.
                        resetFirestore()
                        // Enable network BEFORE setting userId — setting userId
                        // triggers flatMapLatest to register snapshot listeners,
                        // which need the network already enabled to reach the server.
                        _isGoogleSignedIn.value = true
                        enableNetwork()
                        _currentUserId.value = googleUser.uid
                        return@withContext true
                    }
                    return@withContext false
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
     * Check if user is signed in with Google.
     */
    fun isSignedIn(): Boolean = _isGoogleSignedIn.value

    /**
     * Get the current user ID (Google UID or local UUID).
     */
    fun getUserId(): String? = _currentUserId.value

    /**
     * Sign out from Google account and clear all local data.
     * Produces a clean slate equivalent to a fresh install: empty Firestore cache,
     * no locally stored images, and a new local user ID.
     * Remote data on the Google account is NOT deleted.
     */
    suspend fun signOut() {
        withContext(Dispatchers.IO) {
            if (!_isGoogleSignedIn.value) return@withContext

            // Sign out of Firebase Auth and clear credential state
            auth.signOut()
            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear credential state during sign-out", e)
            }

            // Terminate and clear Firestore persistence (pending writes + cache).
            // This replaces the old approach of deleting files on disk, which was
            // unreliable while the Firestore instance was still running.
            resetFirestore()

            // Delete locally stored recipe images
            val imageDir = java.io.File(context.filesDir, "recipe_images")
            if (imageDir.exists()) {
                val deleted = imageDir.deleteRecursively()
                Log.d(TAG, "Deleted recipe images dir: $deleted")
            }

            // Generate a new local user ID (clean slate)
            val newLocalId = "local_${java.util.UUID.randomUUID()}"
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit { putString(KEY_LOCAL_USER_ID, newLocalId) }

            // Disable network BEFORE setting userId — new local user should
            // never contact the server.
            _isGoogleSignedIn.value = false
            disableNetwork()
            _currentUserId.value = newLocalId
            Log.d(TAG, "Signed out and cleared local data. New local user: $newLocalId")
        }
    }

    /**
     * Copy all recipes and meal plans from one user to another.
     * Used when transitioning between local and Google accounts.
     */
    private suspend fun copyUserData(fromUserId: String, toUserId: String) {
        try {
            val fromRecipes = withTimeout(GET_TIMEOUT_MS) {
                firestore.collection(COLLECTION_USERS)
                    .document(fromUserId)
                    .collection(COLLECTION_RECIPES)
                    .get(getSource()).await()
            }

            val fromMealPlans = withTimeout(GET_TIMEOUT_MS) {
                firestore.collection(COLLECTION_USERS)
                    .document(fromUserId)
                    .collection(COLLECTION_MEAL_PLANS)
                    .get(getSource()).await()
            }

            val batch = firestore.batch()
            var recipeCount = 0
            for (doc in fromRecipes.documents) {
                val data = doc.data ?: continue
                val ref = firestore.collection(COLLECTION_USERS)
                    .document(toUserId)
                    .collection(COLLECTION_RECIPES)
                    .document(doc.id)
                batch.set(ref, data)
                recipeCount++
            }

            var mealPlanCount = 0
            for (doc in fromMealPlans.documents) {
                val data = doc.data ?: continue
                val ref = firestore.collection(COLLECTION_USERS)
                    .document(toUserId)
                    .collection(COLLECTION_MEAL_PLANS)
                    .document(doc.id)
                batch.set(ref, data)
                mealPlanCount++
            }

            if (recipeCount + mealPlanCount > 0) {
                batch.commit().await()
            }

            Log.d(TAG, "Copied $recipeCount recipes and $mealPlanCount meal plans from $fromUserId to $toUserId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy user data from $fromUserId to $toUserId", e)
        }
    }

    /**
     * Wait for [_currentUserId] to be available.
     * This suspends until [ensureUser] has completed.
     */
    private suspend fun awaitUserId(): String =
        _currentUserId.filterNotNull().first()

    /**
     * Returns the appropriate Firestore [Source] for one-shot reads.
     * When network is enabled (Google user with sync), uses the default source
     * which tries cache first then falls back to server.
     * When network is disabled (local-only), forces cache-only reads.
     */
    private fun getSource(): Source =
        if (_networkEnabled.value) Source.DEFAULT else Source.CACHE

    private fun userRecipesCollection(userId: String) =
        firestore.collection(COLLECTION_USERS)
            .document(userId)
            .collection(COLLECTION_RECIPES)

    private fun userMealPlansCollection(userId: String) =
        firestore.collection(COLLECTION_USERS)
            .document(userId)
            .collection(COLLECTION_MEAL_PLANS)

    // --- Recipe Operations ---

    /**
     * Upload or update a recipe in Firestore.
     */
    suspend fun upsertRecipe(recipe: Recipe, originalHtml: String?): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val userId = awaitUserId()
                val data = recipeToMap(recipe, originalHtml)
                // Fire-and-forget: Firestore queues locally and syncs when online
                userRecipesCollection(userId).document(recipe.id).set(data)
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to upsert recipe ${recipe.name}", e)
                Result.failure(e)
            }
        }

    /**
     * Delete a recipe document from Firestore.
     */
    suspend fun deleteRecipe(recipeId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val userId = awaitUserId()
                userRecipesCollection(userId).document(recipeId).delete()
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
                val userId = awaitUserId()
                val doc = withTimeout(GET_TIMEOUT_MS) {
                    userRecipesCollection(userId).document(recipeId)
                        .get(getSource()).await()
                }
                if (doc.exists()) parseRecipeDocument(doc) else null
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get recipe: $recipeId", e)
                null
            }
        }

    /**
     * Get all recipes from Firestore (one-shot).
     */
    suspend fun getAllRecipes(): Result<List<RemoteRecipe>> =
        withContext(Dispatchers.IO) {
            try {
                val userId = awaitUserId()
                val snapshot = withTimeout(GET_TIMEOUT_MS) {
                    userRecipesCollection(userId)
                        .get(getSource()).await()
                }

                val recipes = snapshot.documents.mapNotNull { doc ->
                    try {
                        parseRecipeDocument(doc)
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
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeRecipes(): Flow<List<RemoteRecipe>> =
        _currentUserId.filterNotNull().flatMapLatest { userId ->
            callbackFlow {
                Log.d(TAG, "observeRecipes: registering listener (user=$userId)")
                val registration = userRecipesCollection(userId)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            // Always close without error so flatMapLatest keeps
                            // the outer flow alive — a new _currentUserId emission
                            // can restart the listener.
                            if (isTerminationError(error)) {
                                Log.d(TAG, "Recipe snapshot listener closed (instance terminated)")
                            } else {
                                Log.e(TAG, "Recipe snapshot listener error", error)
                                _listenerErrors.tryEmit(error.message ?: "Failed to load recipes")
                            }
                            close()
                            return@addSnapshotListener
                        }

                        val recipes = snapshot?.documents?.mapNotNull { doc ->
                            try {
                                parseRecipeDocument(doc)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to parse recipe from snapshot ${doc.id}", e)
                                null
                            }
                        } ?: emptyList()

                        trySend(recipes)
                    }

                awaitClose { registration.remove() }
            }
        }

    /**
     * Observe a single recipe by ID in real-time.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeRecipeById(recipeId: String): Flow<RemoteRecipe?> =
        _currentUserId.filterNotNull().flatMapLatest { userId ->
            callbackFlow {
                Log.d(TAG, "observeRecipeById: registering listener for $recipeId (user=$userId)")
                val registration = userRecipesCollection(userId).document(recipeId)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            if (isTerminationError(error)) {
                                Log.d(TAG, "Recipe detail listener closed for $recipeId (instance terminated)")
                            } else {
                                Log.e(TAG, "Recipe snapshot listener error for $recipeId", error)
                                _listenerErrors.tryEmit(error.message ?: "Failed to load recipe")
                            }
                            close()
                            return@addSnapshotListener
                        }

                        if (snapshot == null || !snapshot.exists()) {
                            Log.d(TAG, "observeRecipeById: snapshot null/missing for $recipeId (fromCache=${snapshot?.metadata?.isFromCache})")
                            trySend(null)
                            return@addSnapshotListener
                        }

                        try {
                            Log.d(TAG, "observeRecipeById: got data for $recipeId (fromCache=${snapshot.metadata.isFromCache})")
                            trySend(parseRecipeDocument(snapshot))
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse recipe from snapshot $recipeId", e)
                            trySend(null)
                        }
                    }

                awaitClose { registration.remove() }
            }
        }

    /**
     * Update the isFavorite field of a recipe.
     */
    suspend fun setFavorite(recipeId: String, isFavorite: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val userId = awaitUserId()
                userRecipesCollection(userId).document(recipeId)
                    .update("isFavorite", isFavorite)
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set favorite for recipe: $recipeId", e)
                Result.failure(e)
            }
        }

    /**
     * Get all recipe IDs and names (for deduplication checks).
     */
    suspend fun getAllRecipeIdsAndNames(): List<RecipeIdAndName> =
        withContext(Dispatchers.IO) {
            try {
                val userId = awaitUserId()
                val snapshot = withTimeout(GET_TIMEOUT_MS) {
                    userRecipesCollection(userId)
                        .get(getSource()).await()
                }

                snapshot.documents.mapNotNull { doc ->
                    val name = doc.getString("name") ?: return@mapNotNull null
                    RecipeIdAndName(id = doc.id, name = name)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get recipe IDs and names", e)
                emptyList()
            }
        }

    // --- Meal Plan Operations ---

    /**
     * Upload or update a meal plan entry in Firestore.
     */
    suspend fun upsertMealPlan(entry: MealPlanEntry): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val userId = awaitUserId()
                val data = mealPlanToMap(entry)
                userMealPlansCollection(userId).document(entry.id).set(data)
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to upsert meal plan ${entry.id}", e)
                Result.failure(e)
            }
        }

    /**
     * Delete a meal plan document from Firestore.
     */
    suspend fun deleteMealPlan(entryId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val userId = awaitUserId()
                userMealPlansCollection(userId).document(entryId).delete()
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
                val userId = awaitUserId()
                val snapshot = withTimeout(GET_TIMEOUT_MS) {
                    userMealPlansCollection(userId)
                        .get(getSource()).await()
                }

                val entries = snapshot.documents.mapNotNull { doc ->
                    try {
                        parseMealPlanDocument(doc)
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
     * Get a single meal plan entry by ID (one-shot).
     */
    suspend fun getMealPlanById(entryId: String): MealPlanEntry? =
        withContext(Dispatchers.IO) {
            try {
                val userId = awaitUserId()
                val doc = withTimeout(GET_TIMEOUT_MS) {
                    userMealPlansCollection(userId).document(entryId)
                        .get(getSource()).await()
                }
                if (doc.exists()) parseMealPlanDocument(doc) else null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get meal plan: $entryId", e)
                null
            }
        }

    /**
     * Observe all meal plans in real-time via snapshot listener.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeMealPlans(): Flow<List<MealPlanEntry>> =
        _currentUserId.filterNotNull().flatMapLatest { userId ->
            callbackFlow {
                Log.d(TAG, "observeMealPlans: registering listener (user=$userId)")
                val registration = userMealPlansCollection(userId)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            if (isTerminationError(error)) {
                                Log.d(TAG, "Meal plan listener closed (instance terminated)")
                            } else {
                                Log.e(TAG, "Meal plan snapshot listener error", error)
                                _listenerErrors.tryEmit(error.message ?: "Failed to load meal plans")
                            }
                            close()
                            return@addSnapshotListener
                        }

                        val entries = snapshot?.documents?.mapNotNull { doc ->
                            try {
                                parseMealPlanDocument(doc)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to parse meal plan from snapshot ${doc.id}", e)
                                null
                            }
                        } ?: emptyList()

                        trySend(entries)
                    }

                awaitClose { registration.remove() }
            }
        }

    /**
     * Delete all meal plan entries that reference the given recipe.
     */
    suspend fun deleteMealPlansByRecipeId(recipeId: String): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                val userId = awaitUserId()
                val snapshot = withTimeout(GET_TIMEOUT_MS) {
                    userMealPlansCollection(userId)
                        .whereEqualTo("recipeId", recipeId)
                        .get(getSource()).await()
                }

                for (doc in snapshot.documents) {
                    doc.reference.delete()
                }
                Result.success(snapshot.size())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete meal plans for recipe: $recipeId", e)
                Result.failure(e)
            }
        }

    /**
     * Count meal plan entries that reference the given recipe.
     */
    suspend fun countMealPlansByRecipeId(recipeId: String): Int =
        withContext(Dispatchers.IO) {
            try {
                val userId = awaitUserId()
                val snapshot = withTimeout(GET_TIMEOUT_MS) {
                    userMealPlansCollection(userId)
                        .whereEqualTo("recipeId", recipeId)
                        .get(getSource()).await()
                }
                snapshot.size()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to count meal plans for recipe: $recipeId", e)
                0
            }
        }

    // --- Serialization: Domain -> Firestore Maps ---

    private fun recipeToMap(recipe: Recipe, originalHtml: String?): Map<String, Any?> =
        hashMapOf(
            "name" to recipe.name,
            "sourceUrl" to recipe.sourceUrl,
            "story" to recipe.story,
            "servings" to recipe.servings,
            "prepTime" to recipe.prepTime,
            "cookTime" to recipe.cookTime,
            "totalTime" to recipe.totalTime,
            "instructionSections" to recipe.instructionSections.map { instructionSectionToMap(it) },
            "equipment" to recipe.equipment,
            "tags" to recipe.tags,
            "imageUrl" to recipe.imageUrl,
            "sourceImageUrl" to recipe.sourceImageUrl,
            "createdAt" to recipe.createdAt.toFirestoreTimestamp(),
            FIELD_UPDATED_AT to recipe.updatedAt.toFirestoreTimestamp(),
            "isFavorite" to recipe.isFavorite,
            "originalHtml" to (originalHtml ?: "")
        )

    private fun instructionSectionToMap(section: InstructionSection): Map<String, Any?> =
        hashMapOf(
            "name" to section.name,
            "steps" to section.steps.map { instructionStepToMap(it) }
        )

    private fun instructionStepToMap(step: InstructionStep): Map<String, Any?> =
        hashMapOf(
            "stepNumber" to step.stepNumber,
            "instruction" to step.instruction,
            "ingredients" to step.ingredients.map { ingredientToMap(it) },
            "yields" to step.yields,
            "optional" to step.optional
        )

    private fun ingredientToMap(ingredient: Ingredient): Map<String, Any?> =
        hashMapOf(
            "name" to ingredient.name,
            "notes" to ingredient.notes,
            "alternates" to ingredient.alternates.map { ingredientToMap(it) },
            "amount" to ingredient.amount?.let { amountToMap(it) },
            "density" to ingredient.density,
            "optional" to ingredient.optional
        )

    private fun amountToMap(amount: Amount): Map<String, Any?> =
        hashMapOf(
            "value" to amount.value,
            "unit" to amount.unit
        )

    private fun mealPlanToMap(entry: MealPlanEntry): Map<String, Any?> =
        hashMapOf(
            "recipeId" to entry.recipeId,
            "recipeName" to entry.recipeName,
            "recipeImageUrl" to entry.recipeImageUrl,
            "date" to entry.date.toString(),
            "mealType" to entry.mealType.name,
            "servings" to entry.servings,
            "createdAt" to entry.createdAt.toFirestoreTimestamp(),
            FIELD_UPDATED_AT to entry.updatedAt.toFirestoreTimestamp()
        )

    // --- Deserialization: Firestore Documents -> Domain ---

    private fun parseRecipeDocument(doc: DocumentSnapshot): RemoteRecipe? {
        val id = doc.id
        val name = doc.getString("name") ?: return null
        val createdAt = doc.getTimestamp("createdAt")?.toKotlinInstant() ?: return null
        val updatedAt = doc.getTimestamp(FIELD_UPDATED_AT)?.toKotlinInstant() ?: return null

        @Suppress("UNCHECKED_CAST")
        val instructionSectionsRaw = doc.get("instructionSections") as? List<Map<String, Any?>>
        val instructionSections = instructionSectionsRaw?.map { parseInstructionSection(it) } ?: emptyList()

        @Suppress("UNCHECKED_CAST")
        val equipment = doc.get("equipment") as? List<String> ?: emptyList()

        @Suppress("UNCHECKED_CAST")
        val tags = doc.get("tags") as? List<String> ?: emptyList()

        val recipe = Recipe(
            id = id,
            name = name,
            sourceUrl = doc.getString("sourceUrl"),
            story = doc.getString("story"),
            servings = doc.getLong("servings")?.toInt(),
            prepTime = doc.getString("prepTime"),
            cookTime = doc.getString("cookTime"),
            totalTime = doc.getString("totalTime"),
            instructionSections = instructionSections,
            equipment = equipment,
            tags = tags,
            imageUrl = doc.getString("imageUrl"),
            sourceImageUrl = doc.getString("sourceImageUrl"),
            createdAt = createdAt,
            updatedAt = updatedAt,
            isFavorite = doc.getBoolean("isFavorite") ?: false
        )

        val originalHtml = doc.getString("originalHtml")?.ifEmpty { null }
        return RemoteRecipe(recipe, originalHtml)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseInstructionSection(map: Map<String, Any?>): InstructionSection {
        val stepsRaw = map["steps"] as? List<Map<String, Any?>> ?: emptyList()
        return InstructionSection(
            name = map["name"] as? String,
            steps = stepsRaw.map { parseInstructionStep(it) }
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseInstructionStep(map: Map<String, Any?>): InstructionStep {
        val ingredientsRaw = map["ingredients"] as? List<Map<String, Any?>> ?: emptyList()
        return InstructionStep(
            stepNumber = (map["stepNumber"] as? Number)?.toInt() ?: 0,
            instruction = map["instruction"] as? String ?: "",
            ingredients = ingredientsRaw.map { parseIngredient(it) },
            yields = (map["yields"] as? Number)?.toInt() ?: 1,
            optional = map["optional"] as? Boolean ?: false
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseIngredient(map: Map<String, Any?>): Ingredient {
        val alternatesRaw = map["alternates"] as? List<Map<String, Any?>> ?: emptyList()
        val amountRaw = map["amount"] as? Map<String, Any?>
        return Ingredient(
            name = map["name"] as? String ?: "",
            notes = map["notes"] as? String,
            alternates = alternatesRaw.map { parseIngredient(it) },
            amount = amountRaw?.let { parseAmount(it) },
            density = (map["density"] as? Number)?.toDouble(),
            optional = map["optional"] as? Boolean ?: false
        )
    }

    private fun parseAmount(map: Map<String, Any?>): Amount {
        return Amount(
            value = (map["value"] as? Number)?.toDouble(),
            unit = map["unit"] as? String
        )
    }

    private fun parseMealPlanDocument(doc: DocumentSnapshot): MealPlanEntry? {
        val id = doc.id
        val recipeId = doc.getString("recipeId") ?: return null
        val recipeName = doc.getString("recipeName") ?: return null
        val dateStr = doc.getString("date") ?: return null
        val mealTypeStr = doc.getString("mealType") ?: return null
        val createdAt = doc.getTimestamp("createdAt")?.toKotlinInstant() ?: return null
        val updatedAt = doc.getTimestamp(FIELD_UPDATED_AT)?.toKotlinInstant() ?: return null

        val date = try {
            LocalDate.parse(dateStr)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse date '$dateStr' for meal plan $id", e)
            return null
        }

        val mealType = try {
            MealType.valueOf(mealTypeStr)
        } catch (e: Exception) {
            Log.w(TAG, "Unknown meal type '$mealTypeStr' for meal plan $id", e)
            return null
        }

        return MealPlanEntry(
            id = id,
            recipeId = recipeId,
            recipeName = recipeName,
            recipeImageUrl = doc.getString("recipeImageUrl"),
            date = date,
            mealType = mealType,
            servings = doc.getDouble("servings") ?: 1.0,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    // --- Timestamp Conversion Helpers ---

    private fun Instant.toFirestoreTimestamp(): Timestamp {
        return Timestamp(this.epochSeconds, this.nanosecondsOfSecond)
    }

    private fun Timestamp.toKotlinInstant(): Instant {
        return Instant.fromEpochSeconds(this.seconds, this.nanoseconds.toLong())
    }

    /**
     * Returns true if the error is a [FirebaseFirestoreException] with code [CANCELLED],
     * which is the expected error when the Firestore instance is terminated during
     * user transitions (sign-in / sign-out). These are not real errors — the
     * [flatMapLatest] will re-register listeners on the new instance once
     * [_currentUserId] is updated.
     */
    private fun isTerminationError(error: Exception): Boolean =
        error is FirebaseFirestoreException &&
            error.code == FirebaseFirestoreException.Code.CANCELLED
}

/**
 * Represents a recipe fetched from Firestore, including its original HTML.
 */
data class RemoteRecipe(
    val recipe: Recipe,
    val originalHtml: String?
)

/**
 * Lightweight recipe identifier for deduplication checks.
 */
data class RecipeIdAndName(
    val id: String,
    val name: String
)
