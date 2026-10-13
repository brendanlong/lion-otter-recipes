package com.lionotter.recipes.worker

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.inject.Provider

class RunningAccountWorkTest {

    private val work = RunningAccountWork(Provider { error("Not used by this test") })

    @Test
    fun `awaitStopped returns at once when nothing runs`() = runTest {
        work.awaitStopped()
    }

    @Test
    fun `awaitStopped waits for every tracked run, even one that fails`() = runTest {
        val finishFirst = CompletableDeferred<Unit>()
        val finishSecond = CompletableDeferred<Unit>()
        launch { work.track { finishFirst.await() } }
        launch { runCatching { work.track { finishSecond.await(); error("worker failed") } } }
        yield()

        val stopped = async { work.awaitStopped() }
        yield()
        assertFalse(stopped.isCompleted)

        finishFirst.complete(Unit)
        yield()
        assertFalse(stopped.isCompleted)

        finishSecond.complete(Unit)
        stopped.await()
        assertTrue(stopped.isCompleted)
    }
}
