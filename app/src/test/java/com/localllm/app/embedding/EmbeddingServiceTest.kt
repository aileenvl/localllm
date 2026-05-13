package com.localllm.app.embedding

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EmbeddingServiceTest {

    @Test
    fun `meanPool averages only mask=1 rows`() {
        val seq = arrayOf(
            floatArrayOf(1f, 1f, 1f),
            floatArrayOf(3f, 3f, 3f),
            floatArrayOf(99f, 99f, 99f), // masked out
        )
        val mask = intArrayOf(1, 1, 0)
        val out = EmbeddingService.meanPool(seq, mask)
        assertArrayEquals(floatArrayOf(2f, 2f, 2f), out, 1e-5f)
    }

    @Test
    fun `meanPool with all-zero mask returns zero vector`() {
        val seq = arrayOf(
            floatArrayOf(1f, 2f, 3f),
            floatArrayOf(4f, 5f, 6f),
        )
        val mask = intArrayOf(0, 0)
        val out = EmbeddingService.meanPool(seq, mask)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), out, 1e-9f)
    }

    @Test
    fun `meanPool tolerates mask shorter than seq`() {
        val seq = arrayOf(
            floatArrayOf(1f, 1f),
            floatArrayOf(2f, 2f),
            floatArrayOf(3f, 3f),
        )
        // Only one mask entry — only the first row is included.
        val mask = intArrayOf(1)
        val out = EmbeddingService.meanPool(seq, mask)
        assertArrayEquals(floatArrayOf(1f, 1f), out, 1e-5f)
    }

    @Test
    fun `l2Normalize produces unit-norm vector`() {
        val v = floatArrayOf(3f, 4f) // ||v|| = 5
        val n = EmbeddingService.l2Normalize(v)
        assertEquals(0.6f, n[0], 1e-5f)
        assertEquals(0.8f, n[1], 1e-5f)
        val mag = kotlin.math.sqrt(n.fold(0.0) { acc, x -> acc + x * x })
        assertTrue("norm should be 1, got $mag", abs(mag - 1.0) < 1e-5)
    }

    @Test
    fun `l2Normalize handles zero vector without NaN`() {
        val v = floatArrayOf(0f, 0f, 0f)
        val n = EmbeddingService.l2Normalize(v)
        // Output divided by eps; numerically still zero (well below 1).
        n.forEach { x ->
            assertTrue("$x must be finite", x.isFinite())
            assertTrue("$x must be ~0", abs(x) < 1e-3)
        }
    }
}
