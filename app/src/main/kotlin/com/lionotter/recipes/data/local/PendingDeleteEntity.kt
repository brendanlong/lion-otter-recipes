package com.lionotter.recipes.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tracks recipes that have been deleted locally and need to be deleted from
 * Google Drive on the next sync. Without this, a locally-deleted recipe would
 * be re-imported from Drive because the sync logic sees it as "new remote".
 */
@Entity(tableName = "pending_deletes")
data class PendingDeleteEntity(
    @PrimaryKey
    val recipeId: String,
    val recipeName: String,
    val deletedAt: Long
)
