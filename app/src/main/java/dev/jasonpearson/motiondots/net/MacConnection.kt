package dev.jasonpearson.motiondots.net

import dev.jasonpearson.motiondots.protocol.Incoming
import dev.jasonpearson.motiondots.protocol.Messages
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed interface ConnectionState {
    data object Idle : ConnectionState

    data class Connecting(val host: String) : ConnectionState

    data class Connected(val hostName: String) : ConnectionState

    data class Reconnecting(val attempt: Int) : ConnectionState
}

/**
 * WebSocket client for the Mac. Sends hello on open, application level ping every
 * [pingIntervalMs], declares the link dead when no pong arrived within [pongTimeoutMs] and
 * reconnects with [Backoff]. Motion frames pass through [SendGate] and are dropped under load.
 *
 * No android.* imports: runs on the JVM for tests. Everything runs inside [scope].
 */
class MacConnection(
    private val scope: CoroutineScope,
    private val deviceName: String,
    defaultRateHz: Int = Messages.DEFAULT_RATE_HZ,
    private val client: OkHttpClient = defaultClient(),
    private val pingIntervalMs: Long = 2_000L,
    private val pongTimeoutMs: Long = 6_000L,
    private val backoff: Backoff = Backoff(),
    private val gate: SendGate = SendGate(),
    private val monotonicNanos: () -> Long = System::nanoTime,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _rateHz = MutableStateFlow(defaultRateHz)
    /** Rate the Mac asked for (if within 20..60), else the default. */
    val rateHz: StateFlow<Int> = _rateHz.asStateFlow()

    private val _dropped = MutableStateFlow(0L)
    /** Motion frames shed by backpressure. */
    val dropped: StateFlow<Long> = _dropped.asStateFlow()

    @Volatile private var active: Session? = null
    private var job: Job? = null

    private val sb = StringBuilder(160)
    private val sbLock = Any()

    /** Starts (or restarts) connecting to ws://host:port/. Non-blocking. */
    @Synchronized
    fun connect(host: String, port: Int) {
        job?.cancel()
        backoff.reset()
        gate.resetDropped()
        _dropped.value = 0L
        job = scope.launch { runLoop(host, port) }
    }

    /** Stops everything and returns to [ConnectionState.Idle]. */
    @Synchronized
    fun disconnect() {
        job?.cancel()
        job = null
        active?.ws?.close(1000, "bye")
        active = null
        _state.value = ConnectionState.Idle
    }

    /** Sensor thread entry point. Cheap when the gate rejects. Arrays are read synchronously. */
    fun sendMotion(t: Double, a: FloatArray, g: FloatArray) {
        val session = active ?: return
        if (!session.open) return
        val ws = session.ws ?: return
        if (!gate.tryAcquire(ws.queueSize())) {
            _dropped.value = gate.dropped
            return
        }
        val text =
            synchronized(sbLock) {
                sb.setLength(0)
                Messages.appendMotion(sb, t, a, g)
                sb.toString()
            }
        ws.send(text)
    }

    private suspend fun runLoop(host: String, port: Int) {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            _state.value =
                if (attempt == 0) ConnectionState.Connecting(host)
                else ConnectionState.Reconnecting(attempt)
            val session = Session(host)
            val ws = client.newWebSocket(buildRequest(host, port), session.listener)
            session.ws = ws
            active = session
            try {
                supervise(session, ws)
            } finally {
                if (active === session) active = null
                ws.cancel()
            }
            if (session.welcomed) {
                attempt = 0
                backoff.reset()
            }
            attempt++
            _state.value = ConnectionState.Reconnecting(attempt)
            delay(backoff.next())
        }
    }

    /** Returns when the socket closed or the pong deadline passed. */
    private suspend fun supervise(session: Session, ws: WebSocket) {
        while (true) {
            val closed = withTimeoutOrNull(pingIntervalMs) { session.closed.await() }
            if (closed != null) return
            if (!session.open) continue // still connecting; OkHttp's connect timeout will fail it
            val silentNs = monotonicNanos() - session.lastPongNs
            if (silentNs > pongTimeoutMs * 1_000_000L) return
            ws.send(Messages.ping(monotonicNanos() / 1e9))
        }
    }

    private fun adoptRate(hz: Int?) {
        if (Messages.isAdoptableRate(hz)) _rateHz.value = hz!!
    }

    private fun buildRequest(host: String, port: Int): Request {
        val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return Request.Builder().url("ws://$h:$port/").build()
    }

    private inner class Session(val host: String) {
        @Volatile var ws: WebSocket? = null
        @Volatile var open = false
        @Volatile var welcomed = false
        @Volatile var lastPongNs = 0L
        val closed = CompletableDeferred<Unit>()

        val listener =
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    lastPongNs = monotonicNanos()
                    webSocket.send(Messages.hello(deviceName, _rateHz.value))
                    open = true // after hello is queued, so motion can never precede it
                    if (active === this@Session) _state.value = ConnectionState.Connected(host)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    when (val m = Messages.parse(text)) {
                        is Incoming.Welcome -> {
                            welcomed = true
                            adoptRate(m.rateHz)
                            val name = m.host
                            if (name != null && active === this@Session) {
                                _state.value = ConnectionState.Connected(name)
                            }
                        }
                        is Incoming.Pong -> lastPongNs = monotonicNanos()
                        is Incoming.Config -> adoptRate(m.rateHz)
                        null -> Unit
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    closed.complete(Unit)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closed.complete(Unit)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    closed.complete(Unit)
                }
            }
    }

    companion object {
        /** No read timeout and no protocol-level ping: liveness is the app-level ping/pong. */
        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .connectTimeout(5, TimeUnit.SECONDS)
                .pingInterval(0, TimeUnit.MILLISECONDS)
                .build()
    }
}
