package dev.jasonpearson.motiondots.sensors

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AccelFallbackTest {
    @Test
    fun linearIsRawMinusGravity() {
        val out = FloatArray(3)
        AccelFallback.linearFrom(floatArrayOf(0.5f, 0f, 10.31f), floatArrayOf(0f, 0f, 9.81f), out)
        assertArrayEquals(floatArrayOf(0.5f, 0f, 0.5f), out, 1e-5f)
    }

    @Test
    fun atRestFlatLinearIsZero() {
        val lp = GravityLowPass()
        val raw = floatArrayOf(0f, 0f, 9.81f)
        var est = lp.update(raw, 0L)
        for (i in 1..50) est = lp.update(raw, i * 20_000_000L)
        val out = FloatArray(3)
        AccelFallback.linearFrom(raw, est, out)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), out, 1e-4f)
    }

    @Test
    fun lowPassFirstSampleSeedsEstimate() {
        val est = GravityLowPass().update(floatArrayOf(1f, 2f, 3f), 1L)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), est, 0f)
    }

    @Test
    fun lowPassFollowsStepSlowly() {
        val lp = GravityLowPass(tauSeconds = 0.5f)
        lp.update(floatArrayOf(0f, 0f, 9.81f), 0L)
        val est = lp.update(floatArrayOf(5f, 0f, 9.81f), 20_000_000L) // dt 20 ms, alpha 0.9615
        assertEquals(5f * (1f - 0.5f / 0.52f), est[0], 1e-4f)
    }

    @Test
    fun brieflyPushedPhoneShowsPositiveX() {
        val lp = GravityLowPass()
        val rest = floatArrayOf(0f, 0f, 9.81f)
        var est = rest
        for (i in 0..100) est = lp.update(rest, i * 20_000_000L)
        val pushed = floatArrayOf(2f, 0f, 9.81f)
        est = lp.update(pushed, 101 * 20_000_000L)
        val out = FloatArray(3)
        AccelFallback.linearFrom(pushed, est, out)
        assertEquals(true, out[0] > 1.5f)
    }
}
