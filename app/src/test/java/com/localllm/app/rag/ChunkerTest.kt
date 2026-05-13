package com.localllm.app.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkerTest {

    @Test
    fun `text under the cap returns a single chunk`() {
        val out = Chunker.chunk("hello world", maxChars = 400)
        assertEquals(listOf("hello world"), out)
    }

    @Test
    fun `whitespace-only input returns empty`() {
        assertEquals(emptyList<String>(), Chunker.chunk("   \n\n  \n"))
    }

    @Test
    fun `paragraph-aware splitting preserves whole paragraphs when possible`() {
        val p1 = "a".repeat(180)
        val p2 = "b".repeat(180)
        val p3 = "c".repeat(180)
        val out = Chunker.chunk("$p1\n\n$p2\n\n$p3", maxChars = 400, overlapChars = 0)
        // First window holds p1 + p2 (~362 chars with separator); p3 starts a new window.
        assertEquals(2, out.size)
        assertTrue("p1 in first chunk", out[0].contains(p1))
        assertTrue("p2 in first chunk", out[0].contains(p2))
        assertTrue("p3 in second chunk", out[1].contains(p3))
    }

    @Test
    fun `overlap carries tail of previous chunk into next`() {
        val p1 = "a".repeat(180)
        val p2 = "b".repeat(180)
        val p3 = "c".repeat(180)
        val out = Chunker.chunk("$p1\n\n$p2\n\n$p3", maxChars = 400, overlapChars = 60)
        assertEquals(2, out.size)
        // Last 60 chars of chunk[0] should be at the start of chunk[1] (after trim).
        val tail = out[0].takeLast(60)
        assertTrue("overlap tail '$tail' should start chunk[1]", out[1].startsWith(tail))
    }

    @Test
    fun `a single oversized paragraph falls back to sliding window`() {
        val giant = "x".repeat(1000)
        val out = Chunker.chunk(giant, maxChars = 400, overlapChars = 100)
        // 400-char windows, step = 300, so for 1000 chars we expect 4 windows
        // covering [0..400), [300..700), [600..1000), and one more if needed.
        assertTrue("expected >=3 chunks, got ${out.size}", out.size >= 3)
        out.forEach { assertTrue("chunk len <= 400", it.length <= 400) }
        // Total reconstructed length should cover the input.
        val joined = out.joinToString("")
        assertTrue("oversized paragraph should be fully covered", joined.length >= giant.length)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `overlap larger than maxChars rejects`() {
        Chunker.chunk("hi", maxChars = 100, overlapChars = 100)
    }
}
