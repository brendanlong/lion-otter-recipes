package com.lionotter.recipes.data.local.sync

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a pending sync operation that needs to be executed.
 * Used for retry logic and ensuring operations complete even after app restarts.
 *
 * For DELETE operations, we store the expected version at time of queuing.
 * Before executing a delete, we verify the current version matches - if someone
 * else modified the file since we queued the delete, we abort to avoid data loss.
 */
@Entity(
    tableName = "pending_sync_operations",
    indices = [
        Index(value = ["status"]),
        Index(value = ["localRecipeId"]),
        Index(value = ["operationType"])
    ]
)
data class PendingSyncOperationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val operationType: SyncOperationType,

    // For UPLOAD operations - the recipe to upload
    val localRecipeId: String? = null,

    // For DELETE operations - the Drive resources to delete
    val driveFolderId: String? = null,
    val driveFileId: String? = null,

    // Version info at time of queuing - critical for safe deletes
    // If the Drive version has changed since we queued, abort the delete
    val expectedDriveVersion: Long? = null,
    val expectedDriveModifiedTime: Long? = null,

    // Retry tracking
    val createdAt: Long,
    val lastAttemptAt: Long? = null,
    val attemptCount: Int = 0,
    val lastError: String? = null,

    val status: OperationStatus
)
