package com.lionotter.recipes.data.local.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncStateDao {

    @Query("SELECT * FROM sync_state WHERE id = 0")
    suspend fun get(): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE id = 0")
    fun getFlow(): Flow<SyncStateEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(state: SyncStateEntity)

    @Query("UPDATE sync_state SET syncEnabled = :enabled WHERE id = 0")
    suspend fun setSyncEnabled(enabled: Boolean)

    @Query("UPDATE sync_state SET syncFolderId = :folderId, syncFolderName = :folderName WHERE id = 0")
    suspend fun setSyncFolder(folderId: String?, folderName: String?)

    @Query("UPDATE sync_state SET changesPageToken = :token, lastChangesSyncAt = :syncedAt WHERE id = 0")
    suspend fun updateChangesToken(token: String?, syncedAt: Long)

    @Query("UPDATE sync_state SET lastFullSyncAt = :syncedAt WHERE id = 0")
    suspend fun updateLastFullSync(syncedAt: Long)

    @Query("UPDATE sync_state SET lastSyncError = :error WHERE id = 0")
    suspend fun updateLastSyncError(error: String?)

    @Query("SELECT syncEnabled FROM sync_state WHERE id = 0")
    fun isSyncEnabledFlow(): Flow<Boolean?>

    @Query("SELECT changesPageToken FROM sync_state WHERE id = 0")
    suspend fun getChangesPageToken(): String?
}
