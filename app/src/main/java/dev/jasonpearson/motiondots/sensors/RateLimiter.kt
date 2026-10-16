package dev.jasonpearson.motiondots.sensors

/**
 * Throttles a faster sensor stream to [rateHz]. Uses a catch-up schedule so jittery event times
 * still average out to the requested rate. Timestamps are nanoseconds on any monotonic clock.
 */
class RateLimiter(rateHz: Int) {
    // setRate() is called from the service thread while accept() runs on the sensor thread.
    @Volatile private var intervalNs = intervalFor(rateHz)
    @Volatile private var next = 0L
    @Volatile private var started = false

    fun setRate(rateHz: Int) {
        intervalNs = intervalFor(rateHz)
        started = false
    }

    fun reset() {
        started = false
    }

    fun accept(tNs: Long): Boolean {
        if (!started) {
            next = tNs + intervalNs
            started = true
            return true
        }
        val interval = intervalNs
        val tolerance = interval / 20
        if (tNs < next - tolerance) return false
        next = if (tNs - next > interval) tNs + interval else next + interval
        return true
    }

    private fun intervalFor(rateHz: Int): Long = 1_000_000_000L / rateHz.coerceAtLeast(1)
}
