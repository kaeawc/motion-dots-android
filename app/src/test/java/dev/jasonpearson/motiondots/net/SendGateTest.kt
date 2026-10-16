package dev.jasonpearson.motiondots.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendGateTest {
    @Test
    fun defaultThresholdIsAboutFourHundredBytes() {
        assertTrue(SendGate().maxQueueBytes in 380L..420L)
    }

    @Test
    fun allowsUpToThreeFramesQueuedThenTailDrops() {
        val gate = SendGate(approxFrameBytes = 100)
        assertEquals(300L, gate.maxQueueBytes)
        assertTrue(gate.tryAcquire(0))
        assertTrue(gate.tryAcquire(300))
        assertFalse(gate.tryAcquire(301))
        assertFalse(gate.tryAcquire(10_000))
        assertEquals(2L, gate.dropped)
    }

    @Test
    fun recoversAsSoonAsTheQueueDrains() {
        val gate = SendGate(approxFrameBytes = 100)
        assertFalse(gate.tryAcquire(500))
        assertTrue(gate.tryAcquire(0))
        assertEquals(1L, gate.dropped)
    }

    @Test
    fun rejectedSendCountsAsDrop() {
        val gate = SendGate()
        gate.onSendRejected()
        gate.onSendRejected()
        assertEquals(2L, gate.dropped)
    }

    @Test
    fun resetClearsDropped() {
        val gate = SendGate()
        gate.tryAcquire(1_000_000)
        gate.onSendRejected()
        gate.resetDropped()
        assertEquals(0L, gate.dropped)
    }
}
