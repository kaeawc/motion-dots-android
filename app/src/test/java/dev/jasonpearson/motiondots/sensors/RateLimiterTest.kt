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

    @Test
    fun setRateFromAnotherThreadIsSafeAndVisible() {
        val limiter = RateLimiter(50)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val writer = Thread {
            try {
                var hz = 30
                while (!stop.get()) {
                    limiter.setRate(hz)
                    hz = if (hz == 30) 60 else 30
                }
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        val reader = Thread {
            try {
                var t = 0L
                while (!stop.get()) {
                    limiter.accept(t)
                    t += 5_000_000L
                }
            } catch (e: Throwable) {
                failure.set(e)
            }
        }
        writer.start()
        reader.start()
        Thread.sleep(200)
        stop.set(true)
        writer.join(2_000)
        reader.join(2_000)
        assertEquals(null, failure.get())

        // The last write must be visible to the reading thread: 20 Hz means 50 ms spacing.
        limiter.setRate(20)
        var accepted = false
        val check = Thread {
            assertTrue(limiter.accept(0))
            assertFalse(limiter.accept(10_000_000L))
            accepted = limiter.accept(50_000_000L)
        }
        check.start()
        check.join(2_000)
        assertTrue(accepted)
    }
}
