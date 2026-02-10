package com.lionotter.recipes.data.repository

import com.lionotter.recipes.data.local.ImportDebugDao
import com.lionotter.recipes.data.local.ImportDebugEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImportDebugRepository @Inject constructor(
    private val importDebugDao: ImportDebugDao
) {
    fun getAllDebugEntries(): Flow<List<ImportDebugEntity>> {
        return importDebugDao.getAllDebugEntries()
    }

    suspend fun getDebugEntryById(id: String): ImportDebugEntity? {
        return importDebugDao.getDebugEntryById(id)
    }

    suspend fun saveDebugEntry(entry: ImportDebugEntity) {
        importDebugDao.insertDebugEntry(entry)
    }

    suspend fun deleteAllDebugEntries() {
        importDebugDao.deleteAllDebugEntries()
    }
}
