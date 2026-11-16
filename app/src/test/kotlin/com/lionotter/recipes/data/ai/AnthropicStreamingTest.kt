package com.lionotter.recipes.data.ai

import com.anthropic.core.http.StreamResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.stream.Stream

class AnthropicStreamingTest {
    /** Like the SDK's streams: a read blocks until an event arrives, and closing makes it throw. */
    private class BlockingStream(private val events: List<String>) : StreamResponse<String> {
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)

        override fun stream(): Stream<String> = Stream.concat(
            events.stream(),
            Stream.generate<String> {
                reading.countDown()
                closed.await()
                throw IOException("Socket closed")
            }
        )

        override fun close() = closed.countDown()
    }

    @Test
    fun `cancelling a blocked read closes the stream and cancels rather than failing`() = runBlocking {
        val stream = BlockingStream(listOf("a", "b"))
        val received = mutableListOf<String>()
        var thrown: Throwable? = null
        val job = launch(Dispatchers.IO) {
            try {
                stream.forEachCancellable { received += it }
            } catch (e: Throwable) {
                thrown = e
            }
        }
        withContext(Dispatchers.IO) { stream.reading.await() }

        job.cancel()
        job.join()

        assertEquals(listOf("a", "b"), received)
        assertEquals(0L, stream.closed.count)
        assertTrue("Expected a cancellation, got $thrown", thrown is CancellationException)
    }

    @Test
    fun `passes every event and closes the stream`() = runBlocking {
        var closed = false
        val stream = object : StreamResponse<String> {
            override fun stream(): Stream<String> = Stream.of("a", "b")
            override fun close() {
                closed = true
            }
        }
        val received = mutableListOf<String>()

        withContext(Dispatchers.IO) { stream.forEachCancellable { received += it } }

        assertEquals(listOf("a", "b"), received)
        assertTrue(closed)
    }
}
