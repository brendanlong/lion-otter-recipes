package com.lionotter.recipes.imports

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lionotter.recipes.data.local.PendingImportDao
import com.lionotter.recipes.data.local.PendingImportEntity
import com.lionotter.recipes.data.local.PendingImportStatus
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.worker.RecipeImportWorker
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import java.util.UUID

/** In-memory [PendingImportDao] with the same semantics as the Room queries. */
class FakePendingImportDao : PendingImportDao {
    val rows = MutableStateFlow<List<PendingImportEntity>>(emptyList())

    operator fun get(id: String): PendingImportEntity? = rows.value.firstOrNull { it.id == id }

    private fun update(id: String, transform: (PendingImportEntity) -> PendingImportEntity) {
        rows.value = rows.value.map { if (it.id == id) transform(it) else it }
    }

    override fun getAllPendingImports(): Flow<List<PendingImportEntity>> =
        rows.map { list -> list.sortedBy { it.createdAt } }

    override suspend fun getPendingImportById(id: String) = get(id)

    override suspend fun insertPendingImport(entity: PendingImportEntity) {
        rows.value = rows.value.filter { it.id != entity.id } + entity
    }

    override suspend fun updateMetadata(id: String, name: String?, imageUrl: String?, status: PendingImportStatus) =
        update(id) { it.copy(name = name, imageUrl = imageUrl, status = status) }

    override suspend fun updateStatus(id: String, status: PendingImportStatus) = update(id) { it.copy(status = status) }

    override suspend fun updateStatusWithError(id: String, status: PendingImportStatus, error: RecipeError?) =
        update(id) { it.copy(status = status, error = error) }

    override suspend fun updateWorkManagerId(id: String, workManagerId: String) =
        update(id) { it.copy(workManagerId = workManagerId) }

    override suspend fun getPendingImportByWorkManagerId(workManagerId: String) =
        rows.value.firstOrNull { it.workManagerId == workManagerId }

    override suspend fun deletePendingImport(id: String) {
        rows.value = rows.value.filter { it.id != id }
    }

    override suspend fun deleteAll() {
        rows.value = emptyList()
    }
}

/**
 * A [WorkManager] stand-in over an in-memory list of [WorkInfo]s, for the queries the import
 * classes use. Unique work names are registered with [assignUniqueName].
 */
class FakeImportWork {
    val infos = MutableStateFlow<List<WorkInfo>>(emptyList())
    private val uniqueNames = mutableMapOf<String, MutableSet<UUID>>()

    val workManager: WorkManager = mockk {
        every { getWorkInfosFlow(any()) } returns infos
        every { getWorkInfoByIdFlow(any()) } answers {
            val id = firstArg<UUID>()
            infos.map { list -> list.firstOrNull { it.id == id } }
        }
        every { getWorkInfosForUniqueWorkFlow(any()) } answers {
            val name = firstArg<String>()
            infos.map { list -> list.filter { it.id in uniqueNames[name].orEmpty() } }
        }
        every { cancelUniqueWork(any()) } returns mockk()
        every { cancelWorkById(any()) } returns mockk()
    }

    fun assignUniqueName(name: String, id: UUID) {
        uniqueNames.getOrPut(name) { mutableSetOf() } += id
    }

    fun put(info: WorkInfo) {
        infos.value = infos.value.filter { it.id != info.id } + info
    }

    fun remove(id: UUID) {
        infos.value = infos.value.filter { it.id != id }
    }

    operator fun get(id: UUID): WorkInfo? = infos.value.firstOrNull { it.id == id }
}

fun workInfo(
    id: UUID,
    state: WorkInfo.State,
    tag: String = RecipeImportWorker.TAG_RECIPE_IMPORT,
    output: Data = Data.EMPTY,
    progress: Data = Data.EMPTY,
    runAttemptCount: Int = 0
) = WorkInfo(
    id = id,
    state = state,
    tags = setOf(tag),
    outputData = output,
    progress = progress,
    runAttemptCount = runAttemptCount
)

fun pendingImport(
    id: String,
    workManagerId: String?,
    url: String = "https://example.com/$id",
    name: String? = null,
    status: PendingImportStatus = PendingImportStatus.PENDING,
    error: RecipeError? = null,
    createdAt: Long = 0L
) = PendingImportEntity(
    id = id,
    url = url,
    name = name,
    imageUrl = null,
    status = status,
    workManagerId = workManagerId,
    error = error,
    createdAt = createdAt
)

/**
 * An application scope on the test scheduler. Unlike `backgroundScope`, its coroutines are run by
 * `advanceUntilIdle`; long-lived collectors in it simply stay suspended when the test ends.
 */
fun TestScope.testAppScope(): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
