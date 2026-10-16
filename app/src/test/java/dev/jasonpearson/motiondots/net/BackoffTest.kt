package dev.jasonpearson.motiondots.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {
    @Test
    fun sequenceDoublesAndCapsAtTenSeconds() {
        val b = Backoff(jitter = 0.0)
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 10000L, 10000L), List(6) { b.next() })
    }

    @Test
    fun resetRestartsSequence() {
        val b = Backoff(jitter = 0.0)
        repeat(4) { b.next() }
        assertEquals(4, b.attempts())
        b.reset()
        assertEquals(0, b.attempts())
        assertEquals(1000L, b.next())
        assertEquals(2000L, b.next())
    }

    @Test
    fun neverOverflowsAfterManyAttempts() {
        val b = Backoff(jitter = 0.0)
        repeat(200) { assertTrue(b.next() in 1000L..10000L) }
    }

    @Test
    fun jitterLowEndIsMinusTwentyPercent() {
        val b = Backoff(random = { 0.0 })
        assertEquals(listOf(800L, 1600L, 3200L, 6400L, 8000L), List(5) { b.next() })
    }

    @Test
    fun jitterHighEndIsPlusTwentyPercent() {
        val b = Backoff(random = { 1.0 })
        assertEquals(listOf(1200L, 2400L, 4800L, 9600L, 12000L), List(5) { b.next() })
    }

    @Test
    fun midpointRandomMeansNoJitter() {
        val b = Backoff(random = { 0.5 })
        assertEquals(listOf(1000L, 2000L, 4000L), List(3) { b.next() })
    }

    @Test
    fun realRandomStaysWithinBounds() {
        val b = Backoff()
        repeat(500) {
            val d = b.next()
            assertTrue("delay $d", d in 800L..12_000L)
        }
    }
}
