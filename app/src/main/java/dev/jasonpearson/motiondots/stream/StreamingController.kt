package dev.jasonpearson.motiondots.stream

import dev.jasonpearson.motiondots.net.ConnectionState
import dev.jasonpearson.motiondots.net.DiscoveredMac
import dev.jasonpearson.motiondots.protocol.Messages
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide state the service publishes and the UI observes. The service is the only writer.
 */
object StreamingController {
    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _target = MutableStateFlow<DiscoveredMac?>(null)
    val target: StateFlow<DiscoveredMac?> = _target.asStateFlow()

    private val _achievedHz = MutableStateFlow(0f)
    val achievedHz: StateFlow<Float> = _achievedHz.asStateFlow()

    private val _requestedHz = MutableStateFlow(Messages.DEFAULT_RATE_HZ)
    val requestedHz: StateFlow<Int> = _requestedHz.asStateFlow()

    private val _dropped = MutableStateFlow(0L)
    val dropped: StateFlow<Long> = _dropped.asStateFlow()

    private val _linearAccel = MutableStateFlow(floatArrayOf(0f, 0f, 0f))
    /** Latest linear acceleration (ax, ay, az) for the debug readout, about 10 Hz. */
    val linearAccel: StateFlow<FloatArray> = _linearAccel.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    /** One-line problem to show in the UI (no Mac found, no sensor...). */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun setStreaming(value: Boolean) { _streaming.value = value }
    fun setConnection(value: ConnectionState) { _connection.value = value }
    fun setTarget(value: DiscoveredMac?) { _target.value = value }
    fun setAchievedHz(value: Float) { _achievedHz.value = value }
    fun setRequestedHz(value: Int) { _requestedHz.value = value }
    fun setDropped(value: Long) { _dropped.value = value }
    fun setLinearAccel(value: FloatArray) { _linearAccel.value = value }
    fun setMessage(value: String?) { _message.value = value }

    fun resetRuntime() {
        _streaming.value = false
        _connection.value = ConnectionState.Idle
        _achievedHz.value = 0f
        _linearAccel.value = floatArrayOf(0f, 0f, 0f)
    }
}
