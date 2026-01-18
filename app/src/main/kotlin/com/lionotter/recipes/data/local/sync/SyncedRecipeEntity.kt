package com.lionotter.recipes.data.local.sync

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lionotter.recipes.data.local.RecipeEntity

/**
 * Tracks the sync relationship between a local recipe and its Google Drive representation.
 * This allows us to know which recipes have been synced, their Drive IDs, and version info
 * for safe conflict detection and delete operations.
 */
@Entity(
    tableName = "synced_recipes",
    foreignKeys = [
        ForeignKey(
            entity = RecipeEntity::class,
            parentColumns = ["id"],
            childColumns = ["localRecipeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["localRecipeId"]),
        Index(value = ["driveFolderId"]),
        Index(value = ["driveJsonFileId"])
    ]
)
data class SyncedRecipeEntity(
    @PrimaryKey
    val localRecipeId: String,

    // Drive identifiers
    val driveFolderId: String,
    val driveJsonFileId: String,

    // Version tracking - critical for safe operations
    // Drive's file version number (monotonically increasing)
    val driveVersion: Long,
    // Drive's modifiedTime as epoch milliseconds
    val driveModifiedTime: Long,
    // MD5 checksum of the file content for verification
    val driveMd5Checksum: String,

    // Sync timestamps
    // When we last successfully synced this recipe
    val lastSyncedAt: Long,
    // Local updatedAt value at time of last sync (for detecting local changes)
    val localModifiedAt: Long,

    // Current sync status
    val syncStatus: SyncStatus
)
