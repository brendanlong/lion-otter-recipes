package com.lionotter.recipes.util

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ErrorLogTest {
    private val recorded = mutableListOf<Throwable>()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        ErrorLog.recorder = { recorded.add(it) }
    }

    @After
    fun tearDown() {
        ErrorLog.recorder = null
        unmockkStatic(Log::class)
    }

    @Test
    fun `records the throwable`() {
        val error = IllegalStateException("boom")
        ErrorLog.e("Tag", "failed", error)
        assertEquals(listOf<Throwable>(error), recorded)
    }

    @Test
    fun `message-only errors are not recorded`() {
        ErrorLog.e("Tag", "failed")
        assertEquals(emptyList<Throwable>(), recorded)
    }

    @Test
    fun `a failing recorder doesn't throw`() {
        ErrorLog.recorder = { throw RuntimeException("recorder broke") }
        ErrorLog.e("Tag", "failed", IllegalStateException("boom"))
    }
}
