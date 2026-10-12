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
    val status: PendingImportStatus,
    val workManagerId: String?,
    val errorMessage: String?,
    val createdAt: Long
)
