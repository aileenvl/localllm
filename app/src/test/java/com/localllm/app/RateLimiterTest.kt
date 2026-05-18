package com.localllm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {

    @Test
    fun `bursts up to capacity then 429s`() {
        // 1 token/sec, burst 3 — first 3 requests are free, the 4th waits.
        val rl = RateLimiter(ratePerSec = 1.0, burst = 3.0)
        val t0 = 1_000_000_000L
        assertNull(rl.tryAcquire("ua-A", t0))
        assertNull(rl.tryAcquire("ua-A", t0))
        assertNull(rl.tryAcquire("ua-A", t0))
        val wait = rl.tryAcquire("ua-A", t0)
        assertNotNull("4th request must be limited", wait)
        assertTrue("Retry-After must be >= 1s", wait!! >= 1)
    }

    @Test
    fun `clients have independent buckets`() {
        val rl = RateLimiter(ratePerSec = 1.0, burst = 2.0)
        val t0 = 0L
        assertNull(rl.tryAcquire("ua-A", t0))
        assertNull(rl.tryAcquire("ua-A", t0))
        // Bucket A exhausted, but B is fresh.
        assertNotNull(rl.tryAcquire("ua-A", t0))
        assertNull(rl.tryAcquire("ua-B", t0))
        assertNull(rl.tryAcquire("ua-B", t0))
    }

    @Test
    fun `bucket refills at advertised rate`() {
        val rl = RateLimiter(ratePerSec = 2.0, burst = 2.0)
        val t0 = 0L
        // Drain the bucket
        assertNull(rl.tryAcquire("c", t0))
        assertNull(rl.tryAcquire("c", t0))
        assertNotNull(rl.tryAcquire("c", t0))
        // After 1 second, 2 tokens have refilled (capped at burst=2)
        val t1 = t0 + 1_000_000_000L
        assertNull(rl.tryAcquire("c", t1))
        assertNull(rl.tryAcquire("c", t1))
        assertNotNull(rl.tryAcquire("c", t1))
    }

    @Test
    fun `Retry After is always at least 1 second`() {
        // High rate so even a small deficit could round to zero
        // — must still surface at least 1s for a meaningful Retry-After.
        val rl = RateLimiter(ratePerSec = 100.0, burst = 1.0)
        val t0 = 0L
        assertNull(rl.tryAcquire("c", t0))
        val wait = rl.tryAcquire("c", t0 + 1_000_000L) // 1ms later
        assertNotNull(wait)
        assertEquals(1L, wait)
    }

    @Test
    fun `reset clears buckets`() {
        val rl = RateLimiter(ratePerSec = 1.0, burst = 1.0)
        val t0 = 0L
        assertNull(rl.tryAcquire("c", t0))
        assertNotNull(rl.tryAcquire("c", t0))
        rl.reset()
        assertNull("after reset, bucket should be full again", rl.tryAcquire("c", t0))
    }
}
