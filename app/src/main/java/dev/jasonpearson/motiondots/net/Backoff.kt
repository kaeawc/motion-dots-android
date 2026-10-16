package dev.jasonpearson.motiondots.net

/** Reconnect delays: 1s, 2s, 4s, ... capped at 10s. [reset] after a healthy connection. */
class Backoff(private val initialMs: Long = 1_000L, private val maxMs: Long = 10_000L) {
    private var attempt = 0

    /** Returns the delay for the next retry and advances the sequence. */
    @Synchronized
    fun next(): Long {
        var d = initialMs
        repeat(attempt) { d = minOf(d * 2, maxMs) }
        if (attempt < 30) attempt++
        return minOf(d, maxMs)
    }

    @Synchronized
    fun reset() {
        attempt = 0
    }
}
