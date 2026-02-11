package com.lionotter.recipes.data.repository

import com.lionotter.recipes.data.local.SyncLogDao
import com.lionotter.recipes.data.local.SyncLogEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncLogRepository @Inject constructor(
    private val syncLogDao: SyncLogDao
) {
    fun getAllLogs(): Flow<List<SyncLogEntity>> = syncLogDao.getAllLogs()

    suspend fun addLog(level: String, tag: String, message: String) {
        val log = SyncLogEntity(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message
        )
        syncLogDao.insertLog(log)

        if (syncLogDao.getLogCount() > 550) {
            syncLogDao.pruneOldLogs()
        }
    }

    suspend fun clearAllLogs() {
        syncLogDao.clearAllLogs()
    }
}
