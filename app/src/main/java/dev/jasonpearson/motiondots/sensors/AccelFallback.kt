package dev.jasonpearson.motiondots.sensors

/** Helpers for deriving linear acceleration from the raw accelerometer. Pure Kotlin. */
object AccelFallback {
    /** out = raw - gravity, component-wise. */
    fun linearFrom(raw: FloatArray, gravity: FloatArray, out: FloatArray) {
        out[0] = raw[0] - gravity[0]
        out[1] = raw[1] - gravity[1]
        out[2] = raw[2] - gravity[2]
    }
}

/** Low-pass gravity estimate for devices without TYPE_GRAVITY: alpha = tau / (tau + dt). */
class GravityLowPass(private val tauSeconds: Float = 0.5f) {
    private val estimate = FloatArray(3)
    private var lastNs = -1L

    /** Feeds a raw accelerometer reading and returns the updated estimate (internal array). */
    fun update(raw: FloatArray, tNs: Long): FloatArray {
        if (lastNs < 0L) {
            raw.copyInto(estimate, endIndex = 3)
        } else {
            val dt = ((tNs - lastNs) / 1e9f).coerceAtLeast(0f)
            val alpha = tauSeconds / (tauSeconds + dt)
            for (i in 0 until 3) estimate[i] = alpha * estimate[i] + (1f - alpha) * raw[i]
        }
        lastNs = tNs
        return estimate
    }

    fun reset() {
        lastNs = -1L
    }
}
