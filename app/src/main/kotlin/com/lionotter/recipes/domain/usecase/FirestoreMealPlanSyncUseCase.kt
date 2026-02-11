package com.lionotter.recipes.domain.usecase

import android.util.Log
import com.lionotter.recipes.data.remote.FirestoreService
import com.lionotter.recipes.data.repository.MealPlanRepository
import javax.inject.Inject

/**
 * Use case for bidirectional sync of meal plans with Firebase Firestore.
 *
 * Strategy:
 * - Each meal plan entry is a Firestore document: users/{userId}/mealPlans/{entryId}
 * - Sync compares local vs remote by entry ID
 * - Latest updatedAt timestamp wins for conflict resolution
 * - Soft-deleted local entries are removed from Firestore after sync
 */
class FirestoreMealPlanSyncUseCase @Inject constructor(
    private val firestoreService: FirestoreService,
    private val mealPlanRepository: MealPlanRepository
) {
    companion object {
        private const val TAG = "FirestoreMealPlanSync"
    }

    data class SyncResult(
        val uploaded: Int = 0,
        val downloaded: Int = 0,
        val updated: Int = 0,
        val deleted: Int = 0
    )

    /**
     * Sync meal plans with Firestore.
     * Call this as part of the regular Firebase sync.
     */
    suspend fun sync(): SyncResult {
        if (!firestoreService.isSignedIn()) return SyncResult()

        return try {
            performSync()
        } catch (e: Exception) {
            Log.e(TAG, "Meal plan sync failed", e)
            SyncResult()
        }
    }

    private suspend fun performSync(): SyncResult {
        // Get all local meal plans (active)
        val localEntries = mealPlanRepository.getAllMealPlansOnce()
        val localById = localEntries.associateBy { it.id }

        // Get soft-deleted local entries
        val deletedEntries = mealPlanRepository.getDeletedMealPlans()
        val deletedIds = deletedEntries.map { it.id }.toSet()

        // Get remote meal plans from Firestore
        val remoteResult = firestoreService.getAllMealPlans()
        if (remoteResult.isFailure) {
            Log.e(TAG, "Failed to fetch remote meal plans", remoteResult.exceptionOrNull())
            return SyncResult()
        }
        val remoteEntries = remoteResult.getOrThrow()
        val remoteById = remoteEntries.associateBy { it.id }

        var uploaded = 0
        var downloaded = 0
        var updated = 0
        var deleted = 0

        // Process all entry IDs from both sides
        val allIds = (localById.keys + remoteById.keys + deletedIds).toSet()

        for (id in allIds) {
            // Skip if this entry was soft-deleted locally
            if (id in deletedIds) {
                if (remoteById.containsKey(id)) {
                    if (firestoreService.hardDeleteMealPlan(id).isSuccess) {
                        deleted++
                    }
                }
                continue
            }

            val local = localById[id]
            val remote = remoteById[id]

            when {
                local != null && remote != null -> {
                    // Both exist - latest wins
                    if (local.updatedAt > remote.updatedAt) {
                        if (firestoreService.upsertMealPlan(local).isSuccess) {
                            updated++
                        }
                    } else if (remote.updatedAt > local.updatedAt) {
                        mealPlanRepository.saveMealPlanFromSync(remote)
                        updated++
                    }
                }
                local != null -> {
                    // Only local - upload
                    if (firestoreService.upsertMealPlan(local).isSuccess) {
                        uploaded++
                    }
                }
                remote != null -> {
                    // Only remote - download
                    mealPlanRepository.saveMealPlanFromSync(remote)
                    downloaded++
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
