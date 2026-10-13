package com.lionotter.recipes.data.repository

import com.lionotter.recipes.data.local.ImportDebugDao
import com.lionotter.recipes.data.local.ImportDebugEntity
import com.lionotter.recipes.domain.port.ImportDebugLog
import com.lionotter.recipes.domain.port.ImportDebugRecord
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock

@Singleton
class ImportDebugRepository @Inject constructor(
    private val importDebugDao: ImportDebugDao
) : ImportDebugLog {
    fun getAllDebugEntries(): Flow<List<ImportDebugEntity>> {
        return importDebugDao.getAllDebugEntries()
    }

    suspend fun getDebugEntryById(id: String): ImportDebugEntity? {
        return importDebugDao.getDebugEntryById(id)
    }

    override suspend fun record(entry: ImportDebugRecord) {
        importDebugDao.insertDebugEntry(
            ImportDebugEntity(
                id = UUID.randomUUID().toString(),
                sourceUrl = entry.sourceUrl,
                originalHtml = entry.originalHtml,
                cleanedContent = entry.cleanedContent,
                aiOutputJson = entry.aiOutputJson,
                originalLength = entry.originalHtml?.length ?: 0,
                cleanedLength = entry.cleanedContent?.length ?: 0,
                inputTokens = entry.inputTokens,
                outputTokens = entry.outputTokens,
                aiModel = entry.aiModel,
                thinkingEnabled = entry.thinkingEnabled,
                recipeId = entry.recipeId,
                recipeName = entry.recipeName,
                errorMessage = entry.errorMessage,
                isError = entry.isError,
                durationMs = entry.durationMs,
                createdAt = Clock.System.now().toEpochMilliseconds(),
                batchMode = entry.batchMode
            )
        )
    }

    suspend fun deleteAllDebugEntries() {
        importDebugDao.deleteAllDebugEntries()
    }
}
