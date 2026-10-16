package dev.jasonpearson.motiondots.sensors

/**
 * Throttles a faster sensor stream to [rateHz]. Uses a catch-up schedule so jittery event times
 * still average out to the requested rate. Timestamps are nanoseconds on any monotonic clock.
 */
class RateLimiter(rateHz: Int) {
    private var intervalNs = intervalFor(rateHz)
    private var next = 0L
    private var started = false

    fun setRate(rateHz: Int) {
        intervalNs = intervalFor(rateHz)
        started = false
    }

    fun reset() {
        started = false
    }

    fun accept(tNs: Long): Boolean {
        if (!started) {
            started = true
            next = tNs + intervalNs
            return true
        }
        val tolerance = intervalNs / 20
        if (tNs < next - tolerance) return false
        next = if (tNs - next > intervalNs) tNs + intervalNs else next + intervalNs
        return true
    }

    private fun intervalFor(rateHz: Int): Long = 1_000_000_000L / rateHz.coerceAtLeast(1)
}
