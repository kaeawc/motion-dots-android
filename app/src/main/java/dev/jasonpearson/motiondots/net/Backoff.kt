package dev.jasonpearson.motiondots.net

/**
 * Reconnect delays: 1s, 2s, 4s, 8s, then 10s, each with +-[jitter] (default 20%) so a fleet of
 * phones does not retry in lockstep. [reset] after a healthy connection.
 *
 * @param random returns a value in [0, 1); injectable for tests.
 */
class Backoff(
    private val initialMs: Long = 1_000L,
    private val maxMs: Long = 10_000L,
    private val jitter: Double = 0.2,
    private val random: () -> Double = { Math.random() },
) {
    private var attempt = 0

    /** Delay for the next retry (jittered) and advances the sequence. */
    @Synchronized
    fun next(): Long {
        val base = baseDelay(attempt)
        if (attempt < 30) attempt++
        if (jitter <= 0.0) return base
        val factor = 1.0 + jitter * (2.0 * random() - 1.0)
        return Math.round(base * factor).coerceAtLeast(1L)
    }

    @Synchronized
    fun reset() {
        attempt = 0
    }

    /** Number of delays handed out since the last [reset] (capped); for tests and diagnostics. */
    @Synchronized
    fun attempts(): Int = attempt

    private fun baseDelay(n: Int): Long {
        var d = initialMs
        repeat(n) { d = minOf(d * 2, maxMs) }
        return minOf(d, maxMs)
    }
}
