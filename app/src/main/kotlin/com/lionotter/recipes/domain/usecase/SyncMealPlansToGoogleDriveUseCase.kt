package com.lionotter.recipes.domain.usecase

import android.util.Log
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.remote.GoogleDriveService
import com.lionotter.recipes.data.repository.MealPlanRepository
import com.lionotter.recipes.domain.model.MealPlanEntry
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Use case for bidirectional sync of meal plans with Google Drive.
 *
 * Strategy:
 * - Meal plans are stored in a "meal-plans" subfolder of the sync folder
 * - Each day gets a separate JSON file (e.g., "2024-01-15.json") to minimize concurrent write conflicts
 * - Each file contains a list of MealPlanEntry objects for that day
 * - Sync compares local vs remote by entry ID within each day file
 * - Soft-deleted local entries are tracked until they're synced to Drive (removed from the day file)
 */
class SyncMealPlansToGoogleDriveUseCase @Inject constructor(
    private val googleDriveService: GoogleDriveService,
    private val mealPlanRepository: MealPlanRepository,
    private val settingsDataStore: SettingsDataStore,
    private val json: Json
) {
    companion object {
        private const val TAG = "SyncMealPlans"
        private const val MEAL_PLANS_FOLDER_NAME = "meal-plans"
    }

    data class SyncResult(
        val uploaded: Int = 0,
        val downloaded: Int = 0,
        val updated: Int = 0,
        val deleted: Int = 0
    )

    /**
     * Sync meal plans with Google Drive.
     * Call this as part of the regular Google Drive sync.
     */
    suspend fun sync(syncFolderId: String): SyncResult {
        if (!googleDriveService.isSignedIn()) return SyncResult()

        val syncEnabled = settingsDataStore.googleDriveSyncEnabled.first()
        if (!syncEnabled) return SyncResult()

        return try {
            performSync(syncFolderId)
        } catch (e: Exception) {
            Log.e(TAG, "Meal plan sync failed", e)
            SyncResult()
        }
    }

    private suspend fun performSync(syncFolderId: String): SyncResult {
        // Find or create the meal-plans subfolder
        val mealPlansFolderResult = googleDriveService.findOrCreateFolder(
            MEAL_PLANS_FOLDER_NAME,
            syncFolderId
        )
        if (mealPlansFolderResult.isFailure) {
            Log.e(TAG, "Failed to find/create meal-plans folder", mealPlansFolderResult.exceptionOrNull())
            return SyncResult()
        }
        val mealPlansFolder = mealPlansFolderResult.getOrThrow()

        // Get all local meal plans (active)
        val localEntries = mealPlanRepository.getAllMealPlansOnce()
        val localEntriesByDate = localEntries.groupBy { it.date }

        // Get soft-deleted local entries (need to remove from remote)
        val deletedEntries = mealPlanRepository.getDeletedMealPlans()
        val deletedIds = deletedEntries.map { it.id }.toSet()
        val deletedByDate = deletedEntries.groupBy { it.date }

        // Get all remote day files
        val remoteFilesResult = googleDriveService.listFiles(mealPlansFolder.id)
        if (remoteFilesResult.isFailure) {
            Log.e(TAG, "Failed to list meal plan files", remoteFilesResult.exceptionOrNull())
            return SyncResult()
        }
        val remoteFiles = remoteFilesResult.getOrThrow()
            .filter { it.name.endsWith(".json") }

        // Parse remote files into date -> entries map
        val remoteEntriesByDate = mutableMapOf<LocalDate, List<MealPlanEntry>>()
        val remoteFileIdByDate = mutableMapOf<LocalDate, String>()

        for (file in remoteFiles) {
            val datePart = file.name.removeSuffix(".json")
            val date = try {
                LocalDate.parse(datePart)
            } catch (_: Exception) {
                continue
            }

            val contentResult = googleDriveService.downloadTextFile(file.id)
            if (contentResult.isSuccess) {
                try {
                    val entries = json.decodeFromString<List<MealPlanEntry>>(contentResult.getOrThrow())
                    remoteEntriesByDate[date] = entries
                    remoteFileIdByDate[date] = file.id
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse meal plan file ${file.name}", e)
                }
            }
        }

        var uploaded = 0
        var downloaded = 0
        var updated = 0
        var deleted = 0

        // Collect all dates that need processing
        val allDates = (localEntriesByDate.keys + remoteEntriesByDate.keys + deletedByDate.keys).toSet()

        for (date in allDates) {
            val localForDate = localEntriesByDate[date] ?: emptyList()
            val remoteForDate = remoteEntriesByDate[date] ?: emptyList()
            val deletedForDate = deletedByDate[date] ?: emptyList()

            val localById = localForDate.associateBy { it.id }
            val remoteById = remoteForDate.associateBy { it.id }

            val mergedEntries = mutableListOf<MealPlanEntry>()
            var dateChanged = false

            // Process entries that exist on both sides
            for (id in (localById.keys + remoteById.keys)) {
                // Skip if this entry was soft-deleted locally
                if (id in deletedIds) {
                    // Don't include in merged - effectively delete from remote
                    if (remoteById.containsKey(id)) {
                        deleted++
                        dateChanged = true
                    }
                    continue
                }

                val local = localById[id]
                val remote = remoteById[id]

                when {
                    local != null && remote != null -> {
                        // Both exist - latest wins
                        if (local.updatedAt > remote.updatedAt) {
                            mergedEntries.add(local)
                            if (local != remote) {
                                updated++
                                dateChanged = true
                            }
                        } else if (remote.updatedAt > local.updatedAt) {
                            mergedEntries.add(remote)
                            mealPlanRepository.saveMealPlanFromSync(remote)
                            updated++
                            dateChanged = true
                        } else {
                            mergedEntries.add(local)
                        }
                    }
                    local != null -> {
                        // Only local - upload
                        mergedEntries.add(local)
                        uploaded++
                        dateChanged = true
                    }
                    remote != null -> {
                        // Only remote - download
                        mergedEntries.add(remote)
                        mealPlanRepository.saveMealPlanFromSync(remote)
                        downloaded++
                        dateChanged = true
                    }
                }
            }

            // Write back to Drive if anything changed
            if (dateChanged) {
                val fileContent = json.encodeToString(mergedEntries)
                val existingFileId = remoteFileIdByDate[date]

                if (mergedEntries.isEmpty() && existingFileId != null) {
                    // Delete empty day file
                    googleDriveService.deleteFile(existingFileId)
                } else if (mergedEntries.isNotEmpty()) {
                    if (existingFileId != null) {
                        googleDriveService.updateTextFile(
                            fileId = existingFileId,
                            content = fileContent,
                            mimeType = "application/json"
                        )
                    } else {
                        googleDriveService.uploadJsonFile(
                            fileName = "${date}.json",
                            content = fileContent,
                            parentFolderId = mealPlansFolder.id
                        )
                    }
                }
            }
        }

        // Purge soft-deleted entries now that sync is complete
        if (deletedEntries.isNotEmpty()) {
            mealPlanRepository.purgeDeletedMealPlans()
        }

        return SyncResult(
            uploaded = uploaded,
            downloaded = downloaded,
            updated = updated,
            deleted = deleted
        )
    }
}
