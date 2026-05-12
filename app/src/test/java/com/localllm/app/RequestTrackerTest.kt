package com.localllm.app

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure-JVM tests for [RequestTracker]. No Android dependencies — the tracker
 * uses only kotlinx.coroutines + java.util.concurrent.
 *
 * Each test starts from a clean slate via [reset]; the tracker is a singleton
 * so without this we'd leak state across tests.
 */
class RequestTrackerTest {

    @Before
    fun setUp() = runTest { reset() }

    @After
    fun tearDown() = runTest { reset() }

    private suspend fun reset() {
        RequestTracker.resetAll()
        RequestTracker.clearHistory()
        RequestTracker.resetStats()
    }

    @Test
    fun `enqueue places entry in queue with QUEUED state`() = runTest {
        val entry = RequestTracker.enqueue("m", false, 1, 100)
        assertEquals(RequestTracker.State.QUEUED, entry.state)
        assertEquals(1, RequestTracker.queue.value.size)
        assertNull(RequestTracker.current.value)
    }

    @Test
    fun `markStarted moves entry from queue to current with RUNNING state`() = runTest {
        val entry = RequestTracker.enqueue("m", false, 1, 100)
        RequestTracker.markStarted(entry.id)
        assertTrue(RequestTracker.queue.value.isEmpty())
        val current = RequestTracker.current.value
        assertNotNull(current)
        assertEquals(entry.id, current!!.id)
        assertEquals(RequestTracker.State.RUNNING, current.state)
        assertNotNull(current.startedAt)
    }

    @Test
    fun `markCompleted with no error moves entry to history as COMPLETED`() = runTest {
        val entry = RequestTracker.enqueue("m", false, 1, 100)
        RequestTracker.markStarted(entry.id)
        RequestTracker.markCompleted(entry.id)
        assertNull(RequestTracker.current.value)
        assertEquals(1, RequestTracker.history.value.size)
        assertEquals(RequestTracker.State.COMPLETED, RequestTracker.history.value.first().state)
    }

    @Test
    fun `markCompleted with error records ERRORED state and error message`() = runTest {
        val entry = RequestTracker.enqueue("m", false, 1, 100)
        RequestTracker.markStarted(entry.id)
        RequestTracker.markCompleted(entry.id, error = "boom")
        val finished = RequestTracker.history.value.first()
        assertEquals(RequestTracker.State.ERRORED, finished.state)
        assertEquals("boom", finished.error)
    }

    @Test
    fun `markCompleted with cancelled records CANCELLED state`() = runTest {
        val entry = RequestTracker.enqueue("m", false, 1, 100)
        RequestTracker.markStarted(entry.id)
        RequestTracker.markCompleted(entry.id, cancelled = true)
        assertEquals(RequestTracker.State.CANCELLED, RequestTracker.history.value.first().state)
        assertEquals(1, RequestTracker.stats.value.totalCancelled)
    }

    @Test
    fun `tryEnqueue returns null when queue is at maxDepth`() = runTest {
        repeat(3) { RequestTracker.tryEnqueue("m", false, 1, 100, maxDepth = 3) }
        val overflow = RequestTracker.tryEnqueue("m", false, 1, 100, maxDepth = 3)
        assertNull(overflow)
        assertEquals(3, RequestTracker.queue.value.size)
    }

    @Test
    fun `tryEnqueue succeeds when queue has room`() = runTest {
        val entry = RequestTracker.tryEnqueue("m", false, 1, 100, maxDepth = 8)
        assertNotNull(entry)
        assertEquals(1, RequestTracker.queue.value.size)
    }

    @Test
    fun `recordChunk increments counts on the current entry`() = runTest {
        val entry = RequestTracker.enqueue("m", true, 1, 100)
        RequestTracker.markStarted(entry.id)
        RequestTracker.recordChunk(entry.id, "abc")
        RequestTracker.recordChunk(entry.id, "de")
        val current = RequestTracker.current.value!!
        assertEquals(2, current.chunkCount)
        assertEquals(5, current.outputChars)  // "abc" + "de"
    }

    @Test
    fun `recordChunk is silently ignored when id doesnt match current`() = runTest {
        val entry = RequestTracker.enqueue("m", true, 1, 100)
        RequestTracker.markStarted(entry.id)
        // ID that doesn't belong to current — should be a no-op.
        RequestTracker.recordChunk("9999", "abc")
        assertEquals(0, RequestTracker.current.value!!.chunkCount)
    }

    @Test
    fun `history is bounded at 50 entries`() = runTest {
        repeat(60) {
            val e = RequestTracker.enqueue("m", false, 1, 100)
            RequestTracker.markStarted(e.id)
            RequestTracker.markCompleted(e.id)
        }
        assertEquals(50, RequestTracker.history.value.size)
    }

    @Test
    fun `markCompleted on still-queued entry pulls it from queue into history`() = runTest {
        // Simulates a client disconnect before the request was picked up.
        val entry = RequestTracker.enqueue("m", false, 1, 100)
        RequestTracker.markCompleted(entry.id, error = "disconnected")
        assertTrue(RequestTracker.queue.value.isEmpty())
        assertNull(RequestTracker.current.value)
        assertEquals(1, RequestTracker.history.value.size)
        assertEquals(RequestTracker.State.ERRORED, RequestTracker.history.value.first().state)
    }

    @Test
    fun `stats track totals across multiple requests`() = runTest {
        repeat(3) {
            val e = RequestTracker.enqueue("m", false, 1, 100)
            RequestTracker.markStarted(e.id)
            RequestTracker.markCompleted(e.id)
        }
        val errored = RequestTracker.enqueue("m", false, 1, 100)
        RequestTracker.markStarted(errored.id)
        RequestTracker.markCompleted(errored.id, error = "boom")

        val cancelled = RequestTracker.enqueue("m", false, 1, 100)
        RequestTracker.markStarted(cancelled.id)
        RequestTracker.markCompleted(cancelled.id, cancelled = true)

        val s = RequestTracker.stats.value
        assertEquals(5L, s.totalRequests)
        assertEquals(3L, s.totalCompleted)
        assertEquals(1L, s.totalErrors)
        assertEquals(1L, s.totalCancelled)
    }

    @Test
    fun `entry ids are monotonically increasing`() = runTest {
        val a = RequestTracker.enqueue("m", false, 1, 100)
        val b = RequestTracker.enqueue("m", false, 1, 100)
        val c = RequestTracker.enqueue("m", false, 1, 100)
        assertTrue(a.id.toLong() < b.id.toLong())
        assertTrue(b.id.toLong() < c.id.toLong())
    }

    @Test
    fun `resetStats zeroes stats but preserves history`() = runTest {
        val e = RequestTracker.enqueue("m", false, 1, 100)
        RequestTracker.markStarted(e.id)
        RequestTracker.markCompleted(e.id)
        assertEquals(1, RequestTracker.history.value.size)
        RequestTracker.resetStats()
        assertEquals(0L, RequestTracker.stats.value.totalRequests)
        assertEquals(1, RequestTracker.history.value.size)  // history untouched
    }

    @Test
    fun `clearHistory empties history but keeps stats`() = runTest {
        val e = RequestTracker.enqueue("m", false, 1, 100)
        RequestTracker.markStarted(e.id)
        RequestTracker.markCompleted(e.id)
        RequestTracker.clearHistory()
        assertTrue(RequestTracker.history.value.isEmpty())
        assertEquals(1L, RequestTracker.stats.value.totalRequests)
    }
}
