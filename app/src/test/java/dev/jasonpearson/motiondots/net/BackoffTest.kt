package dev.jasonpearson.motiondots.net

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffTest {
    @Test
    fun sequenceDoublesAndCapsAtTenSeconds() {
        val b = Backoff()
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 10000L, 10000L), List(6) { b.next() })
    }

    @Test
    fun resetRestartsSequence() {
        val b = Backoff()
        repeat(4) { b.next() }
        b.reset()
        assertEquals(1000L, b.next())
        assertEquals(2000L, b.next())
    }

    @Test
    fun neverOverflowsAfterManyAttempts() {
        val b = Backoff()
        repeat(200) { assertEquals(true, b.next() in 1000L..10000L) }
    }
}
