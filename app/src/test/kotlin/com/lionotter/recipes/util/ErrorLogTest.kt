package com.lionotter.recipes.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class ErrorLogTest {
    private val recorded = mutableListOf<Throwable>()
    private val logged = mutableListOf<Pair<LogLevel, String>>()
    private val defaultSink = ErrorLog.sink

    @Before
    fun setUp() {
        ErrorLog.sink = LogSink { level, _, message, _ -> logged += level to message }
        ErrorLog.recorder = { recorded.add(it) }
    }

    @After
    fun tearDown() {
        ErrorLog.recorder = null
        ErrorLog.sink = defaultSink
    }

    @Test
    fun `messages go to the sink at their level`() {
        ErrorLog.w("Tag", "careful")
        ErrorLog.e("Tag", "failed")
        assertEquals(listOf(LogLevel.WARN to "careful", LogLevel.ERROR to "failed"), logged)
    }

    @Test
    fun `warnings are not recorded`() {
        ErrorLog.w("Tag", "careful", IllegalStateException("boom"))
        assertEquals(emptyList<Throwable>(), recorded)
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

    @Test
    fun `quoted JSON input is removed from recorded errors`() {
        val parseError = kotlinx.serialization.SerializationException(
            "Unexpected JSON token at offset 12\nJSON input: {\"name\":\"Grandma's secret cake\"}"
        )
        ErrorLog.e("Tag", "failed", IllegalStateException("wrapper", parseError))

        val recordedError = recorded.single()
        val messages = generateSequence(recordedError) { it.cause }.map { it.message.orEmpty() }.toList()
        assertEquals(2, messages.size)
        assertEquals(false, messages.any { "Grandma" in it })
        assertEquals(
            "kotlinx.serialization.SerializationException: Unexpected JSON token at offset 12",
            messages[1]
        )
    }

    @Test
    fun `errors without JSON input are recorded unchanged`() {
        val error = IllegalStateException("boom")
        ErrorLog.e("Tag", "failed", error)
        assertSame(error, recorded.single())
    }
}
