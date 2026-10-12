package com.lionotter.recipes.data.local

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingImportStatusTest {

    private val converter = PendingImportStatusConverter()

    @Test
    fun `stored values match what earlier versions wrote`() {
        assertEquals(
            listOf(
                "pending", "fetching_metadata", "metadata_ready", "parsing", "saving",
                "submitting_batch", "waiting_for_batch", "failed"
            ),
            PendingImportStatus.entries.map(converter::fromStatus)
        )
    }

    @Test
    fun `every status round trips`() {
        PendingImportStatus.entries.forEach { assertEquals(it, converter.toStatus(converter.fromStatus(it))) }
    }

    @Test
    fun `unknown stored value is read as pending`() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        try {
            assertEquals(PendingImportStatus.PENDING, converter.toStatus("from_the_future"))
        } finally {
            unmockkStatic(Log::class)
        }
    }
}
