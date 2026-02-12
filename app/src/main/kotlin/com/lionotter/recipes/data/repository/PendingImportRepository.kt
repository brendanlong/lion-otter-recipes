package com.lionotter.recipes.data.repository

import com.lionotter.recipes.data.local.PendingImportDao
import com.lionotter.recipes.data.local.PendingImportEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PendingImportRepository @Inject constructor(
    private val pendingImportDao: PendingImportDao
) {
    fun getAllPendingImports(): Flow<List<PendingImportEntity>> {
        return pendingImportDao.getAllPendingImports()
    }

    suspend fun getPendingImportById(id: String): PendingImportEntity? {
        return pendingImportDao.getPendingImportById(id)
    }

    suspend fun insertPendingImport(entity: PendingImportEntity) {
        pendingImportDao.insertPendingImport(entity)
    }

    suspend fun updateMetadata(id: String, name: String?, imageUrl: String?, status: String) {
        pendingImportDao.updateMetadata(id, name, imageUrl, status)
    }

    suspend fun updateStatus(id: String, status: String) {
        pendingImportDao.updateStatus(id, status)
    }

    suspend fun updateStatusWithError(id: String, status: String, errorMessage: String?) {
        pendingImportDao.updateStatusWithError(id, status, errorMessage)
    }

    suspend fun updateWorkManagerId(id: String, workManagerId: String) {
        pendingImportDao.updateWorkManagerId(id, workManagerId)
    }

    suspend fun getPendingImportByWorkManagerId(workManagerId: String): PendingImportEntity? {
        return pendingImportDao.getPendingImportByWorkManagerId(workManagerId)
    }

    suspend fun deletePendingImport(id: String) {
        pendingImportDao.deletePendingImport(id)
    }
}
