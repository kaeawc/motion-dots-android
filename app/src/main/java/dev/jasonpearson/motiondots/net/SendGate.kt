package dev.jasonpearson.motiondots.net

import java.util.concurrent.atomic.AtomicLong

/**
 * Backpressure for droppable motion frames; hello and ping bypass it (must deliver).
 *
 * OkHttp's `WebSocket.queueSize()` is the number of bytes enqueued and not yet written to the
 * socket. There is a single droppable frame type, so the policy is a plain tail drop: a new
 * sample is shed while more than [maxQueueBytes] (3 frames) are already waiting. The newest
 * sample is the one lost, which keeps the Mac's picture of the past intact and lets the queue
 * drain. A `send()` that OkHttp rejects (socket closing, queue full) is also a drop.
 */
class SendGate(private val approxFrameBytes: Long = 135L) {
    /** Queue depth above which a new sample is dropped (about 400 bytes). */
    val maxQueueBytes: Long = 3 * approxFrameBytes

    private val droppedCount = AtomicLong(0)

    /** Number of motion frames shed so far. */
    val dropped: Long
        get() = droppedCount.get()

    /** @param queueBytes current `WebSocket.queueSize()`. Returns true if the frame may be sent. */
    fun tryAcquire(queueBytes: Long): Boolean {
        if (queueBytes > maxQueueBytes) {
            droppedCount.incrementAndGet()
            return false
        }
        return true
    }

    /** Call when `WebSocket.send` returned false for an admitted frame. */
    fun onSendRejected() {
        droppedCount.incrementAndGet()
    }

    fun resetDropped() {
        droppedCount.set(0)
    }
}
