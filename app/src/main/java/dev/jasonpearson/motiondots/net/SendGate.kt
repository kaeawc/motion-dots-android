package dev.jasonpearson.motiondots.net

/**
 * Backpressure for droppable motion frames. hello/ping bypass the gate (must deliver).
 *
 * OkHttp only exposes the queued byte count, not per-message completion, so "in flight" is
 * inferred: every admitted frame counts as in flight until the socket queue is observed empty.
 */
class SendGate(
    private val maxInFlight: Int = 5,
    private val maxQueueBytes: Long = 8 * 1024L,
) {
    private var inFlight = 0

    /** Number of motion frames shed so far. */
    var dropped: Long = 0L
        @Synchronized get
        private set

    /** @param queueBytes current `WebSocket.queueSize()`. Returns true if the frame may be sent. */
    @Synchronized
    fun tryAcquire(queueBytes: Long): Boolean {
        if (queueBytes <= 0L) inFlight = 0
        if (inFlight >= maxInFlight || queueBytes > maxQueueBytes) {
            dropped++
            return false
        }
        inFlight++
        return true
    }

    /** Explicit completion signal, for callers that can observe it. */
    @Synchronized
    fun release() {
        if (inFlight > 0) inFlight--
    }

    @Synchronized
    fun resetDropped() {
        dropped = 0L
    }
}
