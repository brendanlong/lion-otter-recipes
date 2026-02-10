package com.lionotter.recipes.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Database-backed import queue entry. Persists across app restarts so imports
 * can be resumed or displayed even after the process is killed.
 */
@Entity(tableName = "pending_imports")
data class PendingImportEntity(
    @PrimaryKey
    val id: String,
    val url: String,
    val name: String?,
    val imageUrl: String?,
    val status: String,
    val workManagerId: String?,
    val errorMessage: String?,
    val createdAt: Long
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_FETCHING_METADATA = "fetching_metadata"
        const val STATUS_METADATA_READY = "metadata_ready"
        const val STATUS_PARSING = "parsing"
        const val STATUS_SAVING = "saving"
        const val STATUS_FAILED = "failed"
    }
}
