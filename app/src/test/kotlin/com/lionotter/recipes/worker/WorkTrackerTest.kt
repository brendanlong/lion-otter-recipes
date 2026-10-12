package com.lionotter.recipes.worker

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class WorkTrackerTest {

    private val statuses = MutableStateFlow<Map<UUID, WorkStatus<String, String>>>(emptyMap())

    private fun observe(id: UUID): Flow<WorkStatus<String, String>> =
        statuses.map { it[id] ?: WorkStatus.Missing }

    private fun setStatus(id: UUID, status: WorkStatus<String, String>) {
        statuses.value = statuses.value + (id to status)
    }

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `nothing tracked reports null`() = runTest {
        val tracker = WorkTracker(SavedStateHandle(), KEY, this, ::observe)
        assertNull(tracker.status.first())
        assertNull(tracker.workId)
    }

    @Test
    fun `work is tracked before it is enqueued and keeps reporting after it finishes`() = runTest {
        val handle = SavedStateHandle()
        val tracker = WorkTracker(handle, KEY, this, ::observe)
        val id = UUID.randomUUID()
        var trackedAtEnqueue: String? = null

        tracker.start(
            PendingWork(id) {
                trackedAtEnqueue = handle[KEY]
                // Finishes before anyone observes it
                setStatus(id, WorkStatus.Finished("done"))
            }
        )

        assertEquals(id.toString(), trackedAtEnqueue)
        assertEquals(WorkStatus.Finished("done"), tracker.status.first())
    }

    @Test
    fun `failed enqueue stops tracking and rethrows`() = runTest {
        val tracker = WorkTracker(SavedStateHandle(), KEY, this, ::observe)

        val error = runCatching { tracker.start(PendingWork(UUID.randomUUID()) { throw IOException("disk full") }) }

        assertTrue(error.exceptionOrNull() is IOException)
        assertNull(tracker.workId)
    }

    @Test
    fun `tracked work survives recreation from saved state`() = runTest {
        val id = UUID.randomUUID()
        setStatus(id, WorkStatus.Running("parsing"))
        val handle = SavedStateHandle(mapOf(KEY to id.toString()))

        val tracker = WorkTracker(handle, KEY, this, ::observe)
        advanceUntilIdle()

        assertEquals(id, tracker.workId)
        assertEquals(WorkStatus.Running("parsing"), tracker.status.first())
    }

    @Test
    fun `restored ID whose work no longer exists is dropped`() = runTest {
        val handle = SavedStateHandle(mapOf(KEY to UUID.randomUUID().toString()))

        val tracker = WorkTracker(handle, KEY, this, ::observe)
        advanceUntilIdle()

        assertNull(tracker.workId)
        assertNull(tracker.status.first())
    }

    @Test
    fun `clear stops tracking`() = runTest {
        val tracker = WorkTracker(SavedStateHandle(), KEY, this, ::observe)
        val id = UUID.randomUUID()
        setStatus(id, WorkStatus.Queued)
        tracker.start(PendingWork(id) {})

        tracker.clear()

        assertNull(tracker.status.first())
    }

    private companion object {
        const val KEY = "work"
    }
}
