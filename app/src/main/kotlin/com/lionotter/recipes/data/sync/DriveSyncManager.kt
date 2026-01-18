package com.lionotter.recipes.data.sync

import android.util.Log
import com.lionotter.recipes.data.local.RecipeDao
import com.lionotter.recipes.data.local.sync.OperationStatus
import com.lionotter.recipes.data.local.sync.PendingSyncOperationDao
import com.lionotter.recipes.data.local.sync.PendingSyncOperationEntity
import com.lionotter.recipes.data.local.sync.SyncOperationType
import com.lionotter.recipes.data.local.sync.SyncStateDao
import com.lionotter.recipes.data.local.sync.SyncStateEntity
import com.lionotter.recipes.data.local.sync.SyncStatus
import com.lionotter.recipes.data.local.sync.SyncedRecipeDao
import com.lionotter.recipes.data.local.sync.SyncedRecipeEntity
import com.lionotter.recipes.data.remote.DriveFileMetadata
import com.lionotter.recipes.data.remote.GoogleDriveService
import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.util.RecipeMarkdownFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Google Drive sync operations for recipes.
 * Coordinates uploads, deletes, and change detection.
 */
@Singleton
class DriveSyncManager @Inject constructor(
    private val googleDriveService: GoogleDriveService,
    private val recipeRepository: RecipeRepository,
    private val recipeDao: RecipeDao,
    private val syncedRecipeDao: SyncedRecipeDao,
    private val syncStateDao: SyncStateDao,
    private val pendingOperationDao: PendingSyncOperationDao,
    private val json: Json
) {
    companion object {
        private const val TAG = "DriveSyncManager"
        private const val MIME_TYPE_JSON = "application/json"
        private const val MIME_TYPE_HTML = "text/html"
        private const val MIME_TYPE_MARKDOWN = "text/markdown"
    }

    // ========== State Queries ==========

    /**
     * Whether sync is currently enabled.
     */
    val isSyncEnabled: Flow<Boolean> = syncStateDao.getFlow().map { state ->
        state?.syncEnabled == true && state.syncFolderId != null
    }

    /**
     * Current sync state.
     */
    val syncState: Flow<SyncStateEntity?> = syncStateDao.getFlow()

    /**
     * Number of pending operations.
     */
    val pendingOperationCount: Flow<Int> = pendingOperationDao.getPendingCountFlow()

    /**
     * Number of recipes with conflicts.
     */
    val conflictCount: Flow<Int> = syncedRecipeDao.countByStatusFlow(SyncStatus.CONFLICT)

    // ========== Sync Configuration ==========

    /**
     * Enable sync with the specified Drive folder.
     */
    suspend fun enableSync(folderId: String, folderName: String) {
        val currentState = syncStateDao.get()
        if (currentState == null) {
            syncStateDao.insert(
                SyncStateEntity(
                    syncEnabled = true,
                    syncFolderId = folderId,
                    syncFolderName = folderName
                )
            )
        } else {
            syncStateDao.insert(
                currentState.copy(
                    syncEnabled = true,
                    syncFolderId = folderId,
                    syncFolderName = folderName
                )
            )
        }

        // Initialize the changes page token for incremental sync
        if (currentState?.changesPageToken == null) {
            initializeChangesToken()
        }
    }

    /**
     * Disable sync.
     */
    suspend fun disableSync() {
        syncStateDao.setSyncEnabled(false)
    }

    /**
     * Initialize the changes page token.
     */
    private suspend fun initializeChangesToken() {
        val tokenResult = googleDriveService.getStartPageToken()
        if (tokenResult.isSuccess) {
            syncStateDao.updateChangesToken(
                token = tokenResult.getOrThrow(),
                syncedAt = System.currentTimeMillis()
            )
        }
    }

    // ========== Recipe Sync Operations ==========

    /**
     * Queue a recipe for upload to Drive.
     * Called when a recipe is created or updated locally.
     */
    suspend fun queueUpload(recipeId: String) {
        val syncState = syncStateDao.get()
        if (syncState?.syncEnabled != true || syncState.syncFolderId == null) {
            return
        }

        // Check if there's already a pending upload for this recipe
        val existingOps = pendingOperationDao.getPendingForRecipe(recipeId)
        val hasUpload = existingOps.any { it.operationType == SyncOperationType.UPLOAD }
        if (hasUpload) {
            Log.d(TAG, "Upload already queued for recipe: $recipeId")
            return
        }

        // Mark the synced recipe as pending upload if it exists
        val syncedRecipe = syncedRecipeDao.getByRecipeId(recipeId)
        if (syncedRecipe != null) {
            syncedRecipeDao.updateStatus(recipeId, SyncStatus.PENDING_UPLOAD)
        }

        // Queue the operation
        pendingOperationDao.insert(
            PendingSyncOperationEntity(
                operationType = SyncOperationType.UPLOAD,
                localRecipeId = recipeId,
                createdAt = System.currentTimeMillis(),
                status = OperationStatus.PENDING
            )
        )

        Log.d(TAG, "Queued upload for recipe: $recipeId")
    }

    /**
     * Queue a delete operation for a synced recipe.
     * Called when a recipe is deleted locally.
     *
     * @param recipeId The local recipe ID being deleted
     * @return true if a delete was queued, false if recipe wasn't synced
     */
    suspend fun queueDelete(recipeId: String): Boolean {
        val syncedRecipe = syncedRecipeDao.getByRecipeId(recipeId) ?: return false

        // Queue the delete with version info for safety check
        pendingOperationDao.insert(
            PendingSyncOperationEntity(
                operationType = SyncOperationType.DELETE,
                localRecipeId = recipeId,
                driveFolderId = syncedRecipe.driveFolderId,
                driveFileId = syncedRecipe.driveJsonFileId,
                expectedDriveVersion = syncedRecipe.driveVersion,
                expectedDriveModifiedTime = syncedRecipe.driveModifiedTime,
                createdAt = System.currentTimeMillis(),
                status = OperationStatus.PENDING
            )
        )

        // Mark as pending delete
        syncedRecipeDao.updateStatus(recipeId, SyncStatus.PENDING_DELETE)

        Log.d(TAG, "Queued delete for recipe: $recipeId, folder: ${syncedRecipe.driveFolderId}")
        return true
    }

    // ========== Execute Operations ==========

    /**
     * Execute a pending upload operation.
     */
    suspend fun executeUpload(operation: PendingSyncOperationEntity): UploadResult {
        val recipeId = operation.localRecipeId
            ?: return UploadResult.Error("No recipe ID in operation")

        // Check if recipe still exists
        val recipe = recipeRepository.getRecipeByIdOnce(recipeId)
        if (recipe == null) {
            pendingOperationDao.updateStatus(operation.id, OperationStatus.ABANDONED)
            pendingOperationDao.abandonUploadsForRecipe(recipeId)
            return UploadResult.Abandoned("Recipe no longer exists")
        }

        val syncState = syncStateDao.get()
        val syncFolderId = syncState?.syncFolderId
            ?: return UploadResult.Error("Sync not configured")

        // Check if we've already synced this recipe
        val syncedRecipe = syncedRecipeDao.getByRecipeId(recipeId)

        return if (syncedRecipe != null) {
            // Update existing
            updateRecipeOnDrive(operation, recipe, syncedRecipe)
        } else {
            // Create new
            createRecipeOnDrive(operation, recipe, syncFolderId)
        }
    }

    private suspend fun createRecipeOnDrive(
        operation: PendingSyncOperationEntity,
        recipe: Recipe,
        syncFolderId: String
    ): UploadResult {
        try {
            // Create folder for this recipe
            val folderName = sanitizeFolderName(recipe.name)
            val folderResult = googleDriveService.createFolder(folderName, syncFolderId)
            if (folderResult.isFailure) {
                return UploadResult.Error("Failed to create folder: ${folderResult.exceptionOrNull()?.message}")
            }
            val folder = folderResult.getOrThrow()

            // Upload recipe.json with metadata
            val recipeJson = json.encodeToString(recipe)
            val jsonResult = googleDriveService.uploadTextFileWithMetadata(
                fileName = GoogleDriveService.RECIPE_JSON_FILENAME,
                content = recipeJson,
                mimeType = MIME_TYPE_JSON,
                parentFolderId = folder.id
            )
            if (jsonResult.isFailure) {
                // Try to clean up the folder
                googleDriveService.deleteFile(folder.id)
                return UploadResult.Error("Failed to upload recipe.json: ${jsonResult.exceptionOrNull()?.message}")
            }
            val jsonMetadata = jsonResult.getOrThrow()

            // Upload original HTML if available
            val originalHtml = recipeRepository.getOriginalHtml(recipe.id)
            if (originalHtml != null) {
                googleDriveService.uploadTextFile(
                    fileName = GoogleDriveService.RECIPE_HTML_FILENAME,
                    content = originalHtml,
                    mimeType = MIME_TYPE_HTML,
                    parentFolderId = folder.id
                )
            }

            // Upload markdown version
            val markdown = RecipeMarkdownFormatter.format(recipe)
            googleDriveService.uploadTextFile(
                fileName = GoogleDriveService.RECIPE_MARKDOWN_FILENAME,
                content = markdown,
                mimeType = MIME_TYPE_MARKDOWN,
                parentFolderId = folder.id
            )

            // Record sync state
            val now = System.currentTimeMillis()
            syncedRecipeDao.insert(
                SyncedRecipeEntity(
                    localRecipeId = recipe.id,
                    driveFolderId = folder.id,
                    driveJsonFileId = jsonMetadata.id,
                    driveVersion = jsonMetadata.version,
                    driveModifiedTime = jsonMetadata.modifiedTime,
                    driveMd5Checksum = jsonMetadata.md5Checksum ?: "",
                    lastSyncedAt = now,
                    localModifiedAt = recipe.updatedAt.toEpochMilliseconds(),
                    syncStatus = SyncStatus.SYNCED
                )
            )

            pendingOperationDao.updateStatus(operation.id, OperationStatus.COMPLETED)
            Log.d(TAG, "Created recipe on Drive: ${recipe.name}")
            return UploadResult.Success(jsonMetadata)

        } catch (e: Exception) {
            Log.e(TAG, "Error creating recipe on Drive", e)
            return UploadResult.Error(e.message ?: "Unknown error")
        }
    }

    private suspend fun updateRecipeOnDrive(
        operation: PendingSyncOperationEntity,
        recipe: Recipe,
        syncedRecipe: SyncedRecipeEntity
    ): UploadResult {
        try {
            // Check current Drive version for conflicts
            val metadataResult = googleDriveService.getFileMetadata(syncedRecipe.driveJsonFileId)

            if (metadataResult.isFailure) {
                val exception = metadataResult.exceptionOrNull()
                if (exception is GoogleDriveService.FileNotFoundException) {
                    // File was deleted on Drive, re-create it
                    val syncState = syncStateDao.get()
                    val syncFolderId = syncState?.syncFolderId
                        ?: return UploadResult.Error("Sync not configured")

                    // Delete old sync record and create fresh
                    syncedRecipeDao.deleteByRecipeId(recipe.id)
                    return createRecipeOnDrive(operation, recipe, syncFolderId)
                }
                return UploadResult.Error("Failed to check Drive version: ${exception?.message}")
            }

            val currentMetadata = metadataResult.getOrThrow()

            // Check for conflict - Drive version is newer than what we last synced
            if (currentMetadata.version > syncedRecipe.driveVersion) {
                syncedRecipeDao.updateStatus(recipe.id, SyncStatus.CONFLICT)
                pendingOperationDao.updateStatus(operation.id, OperationStatus.ABANDONED)
                Log.w(TAG, "Conflict detected for recipe: ${recipe.name}")
                return UploadResult.Conflict(currentMetadata)
            }

            // Safe to update
            val recipeJson = json.encodeToString(recipe)
            val updateResult = googleDriveService.updateFile(
                fileId = syncedRecipe.driveJsonFileId,
                content = recipeJson,
                mimeType = MIME_TYPE_JSON
            )
            if (updateResult.isFailure) {
                return UploadResult.Error("Failed to update: ${updateResult.exceptionOrNull()?.message}")
            }
            val newMetadata = updateResult.getOrThrow()

            // Update markdown too
            val markdown = RecipeMarkdownFormatter.format(recipe)
            val mdFile = googleDriveService.findFileInFolder(
                syncedRecipe.driveFolderId,
                GoogleDriveService.RECIPE_MARKDOWN_FILENAME
            )
            if (mdFile != null) {
                googleDriveService.updateFile(mdFile.id, markdown, MIME_TYPE_MARKDOWN)
            } else {
                googleDriveService.uploadTextFile(
                    fileName = GoogleDriveService.RECIPE_MARKDOWN_FILENAME,
                    content = markdown,
                    mimeType = MIME_TYPE_MARKDOWN,
                    parentFolderId = syncedRecipe.driveFolderId
                )
            }

            // Update sync record
            val now = System.currentTimeMillis()
            syncedRecipeDao.insert(
                syncedRecipe.copy(
                    driveVersion = newMetadata.version,
                    driveModifiedTime = newMetadata.modifiedTime,
                    driveMd5Checksum = newMetadata.md5Checksum ?: "",
                    lastSyncedAt = now,
                    localModifiedAt = recipe.updatedAt.toEpochMilliseconds(),
                    syncStatus = SyncStatus.SYNCED
                )
            )

            pendingOperationDao.updateStatus(operation.id, OperationStatus.COMPLETED)
            Log.d(TAG, "Updated recipe on Drive: ${recipe.name}")
            return UploadResult.Success(newMetadata)

        } catch (e: Exception) {
            Log.e(TAG, "Error updating recipe on Drive", e)
            return UploadResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Execute a pending delete operation with version safety check.
     */
    suspend fun executeDelete(operation: PendingSyncOperationEntity): DeleteResult {
        val driveFolderId = operation.driveFolderId
            ?: return DeleteResult.Error("No folder ID in operation")
        val driveFileId = operation.driveFileId
            ?: return DeleteResult.Error("No file ID in operation")

        try {
            // Check if file still exists
            val metadataResult = googleDriveService.getFileMetadata(driveFileId)

            if (metadataResult.isFailure) {
                val exception = metadataResult.exceptionOrNull()
                if (exception is GoogleDriveService.FileNotFoundException) {
                    // Already deleted, clean up
                    cleanupAfterDelete(operation)
                    return DeleteResult.Success
                }
                return DeleteResult.Error("Failed to check file: ${exception?.message}")
            }

            val currentMetadata = metadataResult.getOrThrow()

            // VERSION SAFETY CHECK: Has the file been modified since we queued the delete?
            val expectedVersion = operation.expectedDriveVersion
            val expectedModifiedTime = operation.expectedDriveModifiedTime

            if (expectedVersion != null && currentMetadata.version != expectedVersion) {
                Log.w(TAG, "Delete aborted - version changed. Expected: $expectedVersion, Current: ${currentMetadata.version}")
                pendingOperationDao.updateStatus(operation.id, OperationStatus.ABANDONED)
                // Remove our sync record since we no longer control this file
                operation.localRecipeId?.let { syncedRecipeDao.deleteByRecipeId(it) }
                return DeleteResult.VersionMismatch(currentMetadata)
            }

            if (expectedModifiedTime != null && currentMetadata.modifiedTime != expectedModifiedTime) {
                Log.w(TAG, "Delete aborted - modified time changed")
                pendingOperationDao.updateStatus(operation.id, OperationStatus.ABANDONED)
                operation.localRecipeId?.let { syncedRecipeDao.deleteByRecipeId(it) }
                return DeleteResult.VersionMismatch(currentMetadata)
            }

            // Safe to delete - delete the entire folder
            val deleteResult = googleDriveService.deleteFile(driveFolderId)
            if (deleteResult.isFailure) {
                return DeleteResult.Error("Failed to delete: ${deleteResult.exceptionOrNull()?.message}")
            }

            cleanupAfterDelete(operation)
            Log.d(TAG, "Deleted recipe from Drive, folder: $driveFolderId")
            return DeleteResult.Success

        } catch (e: Exception) {
            Log.e(TAG, "Error deleting from Drive", e)
            return DeleteResult.Error(e.message ?: "Unknown error")
        }
    }

    private suspend fun cleanupAfterDelete(operation: PendingSyncOperationEntity) {
        pendingOperationDao.updateStatus(operation.id, OperationStatus.COMPLETED)
        operation.localRecipeId?.let { syncedRecipeDao.deleteByRecipeId(it) }
    }

    // ========== Process Remote Changes ==========

    /**
     * Process changes from Google Drive using changes.list API.
     * Detects new recipes uploaded from elsewhere and conflicts.
     */
    suspend fun processRemoteChanges(): ChangeProcessingResult {
        val syncState = syncStateDao.get()
        if (syncState?.syncEnabled != true || syncState.syncFolderId == null) {
            return ChangeProcessingResult.NotConfigured
        }

        val pageToken = syncState.changesPageToken
        if (pageToken == null) {
            // Initialize token and try again next time
            initializeChangesToken()
            return ChangeProcessingResult.Initialized
        }

        val changesResult = googleDriveService.listChanges(pageToken, syncState.syncFolderId)
        if (changesResult.isFailure) {
            return ChangeProcessingResult.Error(changesResult.exceptionOrNull()?.message ?: "Unknown error")
        }

        val (changes, newToken) = changesResult.getOrThrow()

        var newRecipes = 0
        var conflicts = 0
        var deletedOnDrive = 0

        for (change in changes) {
            when {
                change.removed -> {
                    // File was deleted on Drive
                    val syncedRecipe = syncedRecipeDao.getByDriveFileId(change.fileId)
                    if (syncedRecipe != null) {
                        syncedRecipeDao.updateStatus(syncedRecipe.localRecipeId, SyncStatus.CONFLICT)
                        deletedOnDrive++
                    }
                }
                change.file != null -> {
                    val file = change.file
                    // Check if this is a recipe.json file we don't know about
                    if (file.name == GoogleDriveService.RECIPE_JSON_FILENAME) {
                        val syncedRecipe = syncedRecipeDao.getByDriveFileId(file.id)
                        if (syncedRecipe == null) {
                            // New recipe from elsewhere - could auto-import
                            // For now, just log it
                            Log.d(TAG, "Detected new recipe.json on Drive: ${file.id}")
                            newRecipes++
                        } else if (file.version > syncedRecipe.driveVersion) {
                            // External modification
                            syncedRecipeDao.updateStatus(syncedRecipe.localRecipeId, SyncStatus.CONFLICT)
                            conflicts++
                        }
                    }
                }
            }
        }

        // Update token
        syncStateDao.updateChangesToken(newToken, System.currentTimeMillis())

        return ChangeProcessingResult.Success(
            newRecipes = newRecipes,
            conflicts = conflicts,
            deletedOnDrive = deletedOnDrive
        )
    }

    // ========== Conflict Resolution ==========

    /**
     * Get all recipes with conflicts.
     */
    suspend fun getConflicts(): List<SyncedRecipeEntity> {
        return syncedRecipeDao.getByStatus(SyncStatus.CONFLICT)
    }

    /**
     * Resolve a conflict by keeping the local version.
     */
    suspend fun resolveConflictKeepLocal(recipeId: String) {
        val syncedRecipe = syncedRecipeDao.getByRecipeId(recipeId) ?: return

        // Force upload local version
        syncedRecipeDao.updateStatus(recipeId, SyncStatus.PENDING_UPLOAD)
        queueUpload(recipeId)
    }

    /**
     * Resolve a conflict by keeping the remote version.
     */
    suspend fun resolveConflictKeepRemote(recipeId: String) {
        val syncedRecipe = syncedRecipeDao.getByRecipeId(recipeId) ?: return

        // Download and overwrite local
        val contentResult = googleDriveService.downloadTextFile(syncedRecipe.driveJsonFileId)
        if (contentResult.isFailure) {
            Log.e(TAG, "Failed to download remote version")
            return
        }

        try {
            val recipe = json.decodeFromString<Recipe>(contentResult.getOrThrow())
            recipeRepository.saveRecipe(recipe)

            // Update sync record
            val metadataResult = googleDriveService.getFileMetadata(syncedRecipe.driveJsonFileId)
            if (metadataResult.isSuccess) {
                val metadata = metadataResult.getOrThrow()
                syncedRecipeDao.insert(
                    syncedRecipe.copy(
                        driveVersion = metadata.version,
                        driveModifiedTime = metadata.modifiedTime,
                        driveMd5Checksum = metadata.md5Checksum ?: "",
                        lastSyncedAt = System.currentTimeMillis(),
                        localModifiedAt = recipe.updatedAt.toEpochMilliseconds(),
                        syncStatus = SyncStatus.SYNCED
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse remote recipe", e)
        }
    }

    // ========== Utilities ==========

    /**
     * Get all pending operations.
     */
    suspend fun getPendingOperations(): List<PendingSyncOperationEntity> {
        return pendingOperationDao.getPendingOperations()
    }

    /**
     * Record an operation attempt failure.
     */
    suspend fun recordOperationFailure(operationId: Long, error: String) {
        pendingOperationDao.recordAttempt(
            id = operationId,
            status = OperationStatus.FAILED_RETRYING,
            attemptAt = System.currentTimeMillis(),
            error = error
        )
    }

    /**
     * Clean up completed and abandoned operations.
     */
    suspend fun cleanupOperations() {
        pendingOperationDao.deleteCompleted()
        pendingOperationDao.deleteAbandoned()
    }

    private fun sanitizeFolderName(name: String): String {
        return name
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(100)
            .ifEmpty { "Untitled Recipe" }
    }

    // ========== Result Types ==========

    sealed class UploadResult {
        data class Success(val metadata: DriveFileMetadata) : UploadResult()
        data class Conflict(val currentMetadata: DriveFileMetadata) : UploadResult()
        data class Error(val message: String) : UploadResult()
        data class Abandoned(val reason: String) : UploadResult()
    }

    sealed class DeleteResult {
        object Success : DeleteResult()
        data class VersionMismatch(val currentMetadata: DriveFileMetadata) : DeleteResult()
        data class Error(val message: String) : DeleteResult()
    }

    sealed class ChangeProcessingResult {
        object NotConfigured : ChangeProcessingResult()
        object Initialized : ChangeProcessingResult()
        data class Success(
            val newRecipes: Int,
            val conflicts: Int,
            val deletedOnDrive: Int
        ) : ChangeProcessingResult()
        data class Error(val message: String) : ChangeProcessingResult()
    }
}
