package dev.jasonpearson.motiondots.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {
    private fun count(hardwareHz: Int, rateHz: Int, seconds: Int = 2): Int {
        val limiter = RateLimiter(rateHz)
        val step = 1_000_000_000L / hardwareHz
        var accepted = 0
        var t = 5_000_000_000L
        repeat(hardwareHz * seconds) {
            if (limiter.accept(t)) accepted++
            t += step
        }
        return accepted
    }

    @Test
    fun firstSampleAlwaysPasses() = assertTrue(RateLimiter(50).accept(123L))

    @Test
    fun throttlesFasterHardware() {
        assertEquals(100.0, count(200, 50).toDouble(), 2.0)
        assertEquals(100.0, count(100, 50).toDouble(), 2.0)
    }

    @Test
    fun slowerHardwarePassesEverything() {
        assertEquals(60, count(30, 50))
    }

    @Test
    fun neverExceedsRequestedRateMuchWhenHardwareIs60() {
        val n = count(60, 30)
        assertTrue("got $n", n in 58..64)
    }

    @Test
    fun rejectsSamplesInsideInterval() {
        val l = RateLimiter(50) // 20 ms
        assertTrue(l.accept(0))
        assertFalse(l.accept(5_000_000))
        assertTrue(l.accept(20_000_000))
    }

    @Test
    fun resyncsAfterGap() {
        val l = RateLimiter(50)
        l.accept(0)
        assertTrue(l.accept(5_000_000_000L))
        assertFalse(l.accept(5_005_000_000L))
    }
}
