package dev.jasonpearson.motiondots.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendGateTest {
    @Test
    fun allowsFiveInFlightThenDrops() {
        val gate = SendGate()
        repeat(5) { assertTrue(gate.tryAcquire(100)) }
        assertFalse(gate.tryAcquire(100))
        assertFalse(gate.tryAcquire(100))
        assertEquals(2L, gate.dropped)
    }

    @Test
    fun emptyQueueClearsInFlight() {
        val gate = SendGate()
        repeat(5) { gate.tryAcquire(100) }
        assertTrue(gate.tryAcquire(0))
        assertEquals(0L, gate.dropped)
    }

    @Test
    fun dropsWhenQueueExceeds8Kb() {
        val gate = SendGate()
        assertFalse(gate.tryAcquire(8 * 1024L + 1))
        assertTrue(gate.tryAcquire(8 * 1024L))
        assertEquals(1L, gate.dropped)
    }

    @Test
    fun releaseFreesSlot() {
        val gate = SendGate()
        repeat(5) { gate.tryAcquire(10) }
        gate.release()
        assertTrue(gate.tryAcquire(10))
    }
}
