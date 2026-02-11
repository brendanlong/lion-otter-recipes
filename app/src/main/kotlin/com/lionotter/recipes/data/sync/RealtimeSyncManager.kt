package com.lionotter.recipes.data.sync

import android.util.Log
import com.google.firebase.firestore.DocumentChange
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.remote.FirestoreService
import com.lionotter.recipes.data.remote.ImageDownloadService
import com.lionotter.recipes.data.repository.ChangeType
import com.lionotter.recipes.data.repository.MealPlanRepository
import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connection state for the real-time sync.
 */
enum class SyncConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

/**
 * Manages real-time bidirectional sync between local Room database and Firebase Firestore.
 *
 * Pull (remote → local): Firestore snapshot listeners deliver document changes (ADDED/MODIFIED/REMOVED).
 * Push (local → remote): Repository change event flows trigger Firestore writes.
 *
 * Loop prevention:
 * 1. saveFromSync() methods don't emit change events, so pushes never see their own remote writes.
 * 2. Timestamp comparison on pull side skips redundant writes when our own push comes back.
 *
 * Initial sync on first enable: After the first snapshot delivers all remote docs, we push any
 * local-only or newer-local items to Firestore, and push deletions for soft-deleted items.
 */
@Singleton
class RealtimeSyncManager @Inject constructor(
    private val firestoreService: FirestoreService,
    private val recipeRepository: RecipeRepository,
    private val mealPlanRepository: MealPlanRepository,
    private val settingsDataStore: SettingsDataStore,
    private val imageDownloadService: ImageDownloadService,
    @ApplicationScope private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "RealtimeSyncManager"
    }

    private val _connectionState = MutableStateFlow(SyncConnectionState.DISCONNECTED)
    val connectionState: StateFlow<SyncConnectionState> = _connectionState.asStateFlow()

    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()

    private var syncJob: Job? = null
    private var recipeListenerJob: Job? = null
    private var mealPlanListenerJob: Job? = null
    private var recipePushJob: Job? = null
    private var mealPlanPushJob: Job? = null

    // Track remote recipe IDs from initial snapshot for initial push
    private var initialRecipeSnapshotReceived = false
    private var initialMealPlanSnapshotReceived = false
    private val remoteRecipeIds = mutableSetOf<String>()
    private val remoteMealPlanIds = mutableSetOf<String>()

    /**
     * Start real-time sync. Attaches snapshot listeners and begins observing local changes.
     * Safe to call multiple times — stops existing sync first.
     */
    fun startSync() {
        stopSync()

        if (!firestoreService.isSignedIn()) {
            Log.w(TAG, "Cannot start sync: not signed in")
            return
        }

        _connectionState.value = SyncConnectionState.CONNECTING
        _syncError.value = null
        initialRecipeSnapshotReceived = false
        initialMealPlanSnapshotReceived = false
        remoteRecipeIds.clear()
        remoteMealPlanIds.clear()

        syncJob = scope.launch {
            // Reset initial sync flag so we always do an initial push when sync starts.
            // This handles cases like: user deletes Firestore data, toggles sync off/on,
            // or switches accounts.
            settingsDataStore.setInitialSyncCompleted(false)
            // Start pull listeners
            recipeListenerJob = launch { observeRecipeChanges() }
            mealPlanListenerJob = launch { observeMealPlanChanges() }

            // Start push observers
            recipePushJob = launch { pushRecipeChanges() }
            mealPlanPushJob = launch { pushMealPlanChanges() }
        }
    }

    /**
     * Stop real-time sync. Removes all listeners and cancels push observers.
     */
    fun stopSync() {
        syncJob?.cancel()
        syncJob = null
        recipeListenerJob = null
        mealPlanListenerJob = null
        recipePushJob = null
        mealPlanPushJob = null
        firestoreService.removeAllListeners()
        _connectionState.value = SyncConnectionState.DISCONNECTED
        initialRecipeSnapshotReceived = false
        initialMealPlanSnapshotReceived = false
        remoteRecipeIds.clear()
        remoteMealPlanIds.clear()
    }

    fun clearSyncError() {
        _syncError.value = null
    }

    // --- Pull: Remote → Local ---

    private suspend fun observeRecipeChanges() {
        try {
            firestoreService.observeRecipeDocumentChanges().collect { changes ->
                for (change in changes) {
                    val docId = change.document.id
                    val data = change.document.data

                    when (change.type) {
                        DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> {
                            remoteRecipeIds.add(docId)
                            if (firestoreService.isRecipeDeleted(data)) {
                                // Remotely deleted — remove locally
                                recipeRepository.hardDeleteRecipe(docId)
                            } else {
                                val remoteRecipe = firestoreService.parseRecipeDocument(data)
                                if (remoteRecipe != null) {
                                    handleRemoteRecipeChange(docId, remoteRecipe.recipe, remoteRecipe.originalHtml)
                                }
                            }
                        }
                        DocumentChange.Type.REMOVED -> {
                            remoteRecipeIds.remove(docId)
                            recipeRepository.hardDeleteRecipe(docId)
                        }
                    }
                }

                if (!initialRecipeSnapshotReceived) {
                    initialRecipeSnapshotReceived = true
                    updateConnectionState()
                    performInitialRecipePush()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recipe snapshot listener failed", e)
            _syncError.value = "Recipe sync failed: ${e.message}"
            _connectionState.value = SyncConnectionState.DISCONNECTED
        }
    }

    private suspend fun handleRemoteRecipeChange(
        docId: String,
        remoteRecipe: Recipe,
        originalHtml: String?
    ) {
        try {
            val localRecipe = recipeRepository.getRecipeByIdOnce(docId)
            val remoteUpdatedAt = remoteRecipe.updatedAt.toEpochMilliseconds()

            if (localRecipe == null || remoteUpdatedAt > localRecipe.updatedAt.toEpochMilliseconds()) {
                val recipeWithLocalImage = downloadImageIfNeeded(remoteRecipe)
                recipeRepository.saveRecipeFromSync(recipeWithLocalImage, originalHtml)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle remote recipe change for $docId", e)
        }
    }

    private suspend fun observeMealPlanChanges() {
        try {
            firestoreService.observeMealPlanDocumentChanges().collect { changes ->
                for (change in changes) {
                    val docId = change.document.id
                    val data = change.document.data

                    when (change.type) {
                        DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> {
                            remoteMealPlanIds.add(docId)
                            if (firestoreService.isMealPlanDeleted(data)) {
                                mealPlanRepository.hardDeleteMealPlan(docId)
                            } else {
                                val entry = firestoreService.parseMealPlanDocument(data)
                                if (entry != null) {
                                    handleRemoteMealPlanChange(docId, entry)
                                }
                            }
                        }
                        DocumentChange.Type.REMOVED -> {
                            remoteMealPlanIds.remove(docId)
                            mealPlanRepository.hardDeleteMealPlan(docId)
                        }
                    }
                }

                if (!initialMealPlanSnapshotReceived) {
                    initialMealPlanSnapshotReceived = true
                    updateConnectionState()
                    performInitialMealPlanPush()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Meal plan snapshot listener failed", e)
            _syncError.value = "Meal plan sync failed: ${e.message}"
            _connectionState.value = SyncConnectionState.DISCONNECTED
        }
    }

    private suspend fun handleRemoteMealPlanChange(
        docId: String,
        remoteEntry: com.lionotter.recipes.domain.model.MealPlanEntry
    ) {
        try {
            val localEntry = mealPlanRepository.getMealPlanByIdOnce(docId)
            if (localEntry == null || remoteEntry.updatedAt > localEntry.updatedAt) {
                mealPlanRepository.saveMealPlanFromSync(remoteEntry)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle remote meal plan change for $docId", e)
        }
    }

    // --- Initial Push: Local → Remote (one-time after first snapshot) ---

    private suspend fun performInitialRecipePush() {
        try {
            val initialSyncDone = settingsDataStore.initialSyncCompleted.first()
            if (initialSyncDone) return

            val localRecipes = recipeRepository.getAllRecipes().first()
            val toPush = localRecipes.filter { it.id !in remoteRecipeIds }
            Log.d(TAG, "Performing initial recipe push: ${toPush.size} of ${localRecipes.size} recipes")

            var failures = 0
            for (recipe in toPush) {
                try {
                    val originalHtml = recipeRepository.getOriginalHtml(recipe.id)
                    val result = firestoreService.upsertRecipe(recipe, originalHtml)
                    if (result.isFailure) {
                        failures++
                        Log.e(TAG, "Failed to push recipe '${recipe.name}': ${result.exceptionOrNull()?.message}")
                    }
                } catch (e: Exception) {
                    failures++
                    Log.e(TAG, "Failed to push recipe '${recipe.name}'", e)
                }
            }

            if (failures > 0) {
                _syncError.value = "Failed to sync $failures of ${toPush.size} recipes"
            }

            // Push soft-deleted recipes to Firestore, then purge
            val deletedRecipes = recipeRepository.getDeletedRecipes()
            for (recipe in deletedRecipes) {
                if (recipe.id in remoteRecipeIds) {
                    firestoreService.hardDeleteRecipe(recipe.id)
                }
            }
            if (deletedRecipes.isNotEmpty()) {
                recipeRepository.purgeDeletedRecipes()
            }

            // Mark initial sync as complete if both snapshots are received
            if (initialMealPlanSnapshotReceived) {
                settingsDataStore.setInitialSyncCompleted(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Initial recipe push failed", e)
            _syncError.value = "Initial recipe sync failed: ${e.message}"
        }
    }

    private suspend fun performInitialMealPlanPush() {
        try {
            val initialSyncDone = settingsDataStore.initialSyncCompleted.first()
            if (initialSyncDone) return

            val localEntries = mealPlanRepository.getAllMealPlansOnce()
            val toPush = localEntries.filter { it.id !in remoteMealPlanIds }
            Log.d(TAG, "Performing initial meal plan push: ${toPush.size} of ${localEntries.size} entries")

            var failures = 0
            for (entry in toPush) {
                try {
                    val result = firestoreService.upsertMealPlan(entry)
                    if (result.isFailure) {
                        failures++
                        Log.e(TAG, "Failed to push meal plan ${entry.id}: ${result.exceptionOrNull()?.message}")
                    }
                } catch (e: Exception) {
                    failures++
                    Log.e(TAG, "Failed to push meal plan ${entry.id}", e)
                }
            }

            if (failures > 0) {
                _syncError.value = "Failed to sync $failures of ${toPush.size} meal plans"
            }

            val deletedEntries = mealPlanRepository.getDeletedMealPlans()
            for (entry in deletedEntries) {
                if (entry.id in remoteMealPlanIds) {
                    firestoreService.hardDeleteMealPlan(entry.id)
                }
            }
            if (deletedEntries.isNotEmpty()) {
                mealPlanRepository.purgeDeletedMealPlans()
            }

            // Mark initial sync as complete if both snapshots are received
            if (initialRecipeSnapshotReceived) {
                settingsDataStore.setInitialSyncCompleted(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Initial meal plan push failed", e)
            _syncError.value = "Initial meal plan sync failed: ${e.message}"
        }
    }

    // --- Ongoing Push: Local → Remote ---

    private suspend fun pushRecipeChanges() {
        recipeRepository.recipeChanges.collect { event ->
            try {
                when (event.changeType) {
                    ChangeType.SAVED -> {
                        val recipe = recipeRepository.getRecipeByIdOnce(event.recipeId)
                        if (recipe != null) {
                            val originalHtml = recipeRepository.getOriginalHtml(event.recipeId)
                            firestoreService.upsertRecipe(recipe, originalHtml)
                        }
                    }
                    ChangeType.DELETED -> {
                        firestoreService.markRecipeDeleted(event.recipeId)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to push recipe change for ${event.recipeId}", e)
            }
        }
    }

    private suspend fun pushMealPlanChanges() {
        mealPlanRepository.mealPlanChanges.collect { event ->
            try {
                when (event.changeType) {
                    ChangeType.SAVED -> {
                        val entry = mealPlanRepository.getMealPlanByIdOnce(event.entryId)
                        if (entry != null) {
                            firestoreService.upsertMealPlan(entry)
                        }
                    }
                    ChangeType.DELETED -> {
                        firestoreService.markMealPlanDeleted(event.entryId)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to push meal plan change for ${event.entryId}", e)
            }
        }
    }

    // --- Utilities ---

    private fun updateConnectionState() {
        if (initialRecipeSnapshotReceived && initialMealPlanSnapshotReceived) {
            _connectionState.value = SyncConnectionState.CONNECTED
        }
    }

    private suspend fun downloadImageIfNeeded(recipe: Recipe): Recipe {
        val imageUrl = recipe.imageUrl ?: return recipe
        if (imageUrl.startsWith("file://")) return recipe
        val localImageUrl = imageDownloadService.downloadAndStore(imageUrl)
        return recipe.copy(imageUrl = localImageUrl)
    }
}
