package com.lionotter.recipes.data.local.sync

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Singleton entity that stores global sync state.
 * Only one row should exist (id = 0).
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey
    val id: Int = 0,

    // Whether sync is enabled
    val syncEnabled: Boolean = false,

    // The Drive folder we sync to
    val syncFolderId: String? = null,
    val syncFolderName: String? = null,

    // changes.list tracking - used for incremental change detection
    val changesPageToken: String? = null,
    val lastChangesSyncAt: Long? = null,

    // Last time we did a full sync
    val lastFullSyncAt: Long? = null,

    // Last sync error message (if any)
    val lastSyncError: String? = null
)
