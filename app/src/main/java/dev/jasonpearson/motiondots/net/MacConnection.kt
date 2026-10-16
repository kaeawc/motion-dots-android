package dev.jasonpearson.motiondots.net

import dev.jasonpearson.motiondots.protocol.Incoming
import dev.jasonpearson.motiondots.protocol.Messages
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
 * WebSocket client for the Mac. Sends hello on open, an application level ping every
 * [pingIntervalMs], declares the link dead when no frame at all (pong or anything else, see the
 * Liveness section of docs/PROTOCOL.md) arrived within [pongTimeoutMs], and reconnects with
 * [Backoff]. Motion frames pass through [SendGate] and are dropped under load.
 *
 * Robustness rules:
 * - A session that has not completed the WebSocket upgrade within [handshakeTimeoutMs] is torn
 *   down (OkHttp reads the upgrade response with readTimeout(0), so it would hang forever).
 * - Every [Session] has a `dead` flag and `live()`; every OkHttp callback bails out first thing if
 *   its session is no longer the live one. State writes are additionally guarded by a generation
 *   counter bumped by [connect] and [disconnect].
 * - The backoff is reset only for a healthy session: welcomed and open for [healthyAfterMs], or
 *   its first pong.
 * - If [retryBudgetMs] passes without a welcomed session, the loop stops and [gaveUp] turns true.
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
    private val handshakeTimeoutMs: Long = 8_000L,
    private val closeGraceMs: Long = 500L,
    private val healthyAfterMs: Long = 10_000L,
    private val retryBudgetMs: Long = 5L * 60_000L,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _rateHz = MutableStateFlow(defaultRateHz)
    /** Rate the Mac asked for (if within 20..60), else the default. */
    val rateHz: StateFlow<Int> = _rateHz.asStateFlow()

    private val _dropped = MutableStateFlow(0L)
    /** Motion frames shed by backpressure. */
    val dropped: StateFlow<Long> = _dropped.asStateFlow()

    private val _gaveUp = MutableStateFlow(false)
    /** True once the retry budget ran out; the loop has stopped and state is Idle. */
    val gaveUp: StateFlow<Boolean> = _gaveUp.asStateFlow()

    private val active = AtomicReference<Session?>(null)
    private val generation = AtomicLong(0)
    private var job: Job? = null

    private val sb = StringBuilder(160)
    private val sbLock = Any()

    /** Starts (or restarts) connecting to ws://host:port/. Non-blocking. */
    @Synchronized
    fun connect(host: String, port: Int) {
        // Bump the generation first: from here on the previous run loop and its callbacks can no
        // longer write state or install a session (see install()).
        val gen = generation.incrementAndGet()
        job?.cancel()
        job = null
        active.getAndSet(null)?.terminate("superseded")
        backoff.reset()
        gate.resetDropped()
        _dropped.value = 0L
        _gaveUp.value = false
        _state.value = ConnectionState.Connecting(host)
        job = scope.launch { runLoop(gen, host, port) }
    }

    /** Stops everything and returns to [ConnectionState.Idle]. Does not block. */
    @Synchronized
    fun disconnect() {
        generation.incrementAndGet()
        job?.cancel()
        job = null
        // Graceful: close(1000), then the socket is cancelled after closeGraceMs if the peer's
        // echo has not arrived (done on a timer thread so the caller, often main, never waits).
        active.getAndSet(null)?.terminate("bye")
        _state.value = ConnectionState.Idle
    }

    /** Sensor thread entry point. Cheap when the gate rejects. Arrays are read synchronously. */
    fun sendMotion(t: Double, a: FloatArray, g: FloatArray) {
        val session = active.get() ?: return
        if (!session.open || session.dead) return
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
        if (!ws.send(text)) {
            gate.onSendRejected()
            _dropped.value = gate.dropped
        }
    }

    /** Writes [s] only if no newer [connect]/[disconnect] has happened since [gen]. */
    private fun setState(gen: Long, s: ConnectionState) {
        _state.update { cur -> if (generation.get() == gen) s else cur }
    }

    /** Makes [session] the active one unless this run loop has been superseded. */
    @Synchronized
    private fun install(gen: Long, session: Session): Boolean {
        if (generation.get() != gen) return false
        active.set(session)
        return true
    }

    private suspend fun runLoop(gen: Long, host: String, port: Int) {
        var attempt = 0
        var unconnectedSinceNs = monotonicNanos()
        while (true) {
            currentCoroutineContext().ensureActive()
            if (generation.get() != gen) return
            setState(
                gen,
                if (attempt == 0) ConnectionState.Connecting(host)
                else ConnectionState.Reconnecting(attempt),
            )
            // The session is created and published as `active` BEFORE the socket exists, so no
            // callback can observe a session that is not yet the active one.
            val session = Session(host, gen)
            if (!install(gen, session)) return
            try {
                try {
                    val ws = client.newWebSocket(buildRequest(host, port), session.listener)
                    session.attach(ws)
                    supervise(session)
                } catch (e: IllegalArgumentException) {
                    // Unparseable host: behaves like a failed attempt (backoff, then retry budget).
                }
            } finally {
                session.terminate("session ended")
                active.compareAndSet(session, null)
            }
            if (generation.get() != gen) return
            evaluateHealthy(session, session.lastSeenNs) // last proof of life, not detection time
            val now = monotonicNanos()
            if (session.welcomed) unconnectedSinceNs = now
            if (session.healthy) attempt = 0
            attempt++
            if (now - unconnectedSinceNs >= retryBudgetMs * 1_000_000L) {
                setState(gen, ConnectionState.Idle)
                if (generation.get() == gen) _gaveUp.value = true
                return
            }
            setState(gen, ConnectionState.Reconnecting(attempt))
            delay(backoff.next())
        }
    }

    /**
     * Returns when the session ended: socket closed or failed, handshake deadline passed, no
     * frame for [pongTimeoutMs], or the session was superseded.
     */
    private suspend fun supervise(session: Session) {
        while (true) {
            val waitMs =
                if (session.open) pingIntervalMs
                else {
                    val elapsedMs = (monotonicNanos() - session.startNs) / 1_000_000L
                    minOf(pingIntervalMs, handshakeTimeoutMs - elapsedMs).coerceAtLeast(1L)
                }
            if (withTimeoutOrNull(waitMs) { session.closed.await() } != null) return
            if (session.dead) return
            val now = monotonicNanos()
            if (!session.open) {
                if (now - session.startNs >= handshakeTimeoutMs * 1_000_000L) {
                    session.terminate("handshake timeout")
                    return
                }
                continue
            }
            // Any inbound frame is proof of life (PROTOCOL.md, Liveness).
            if (now - session.lastSeenNs > pongTimeoutMs * 1_000_000L) {
                session.terminate("no frames from Mac")
                return
            }
            evaluateHealthy(session, now)
            session.ws?.send(Messages.ping(now / 1e9))
        }
    }

    /** Marks the session healthy (and resets the backoff) once welcomed and open long enough. */
    private fun evaluateHealthy(session: Session, atNs: Long) {
        if (session.healthy || !session.welcomed) return
        if (atNs - session.welcomedAtNs >= healthyAfterMs * 1_000_000L) {
            session.markHealthy()
        }
    }

    private fun buildRequest(host: String, port: Int): Request {
        val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return Request.Builder().url("ws://$h:$port/").build()
    }

    private inner class Session(val host: String, val gen: Long) {
        @Volatile var ws: WebSocket? = null
        @Volatile var open = false
        @Volatile var welcomed = false
        @Volatile var welcomedAtNs = 0L
        @Volatile var healthy = false
        @Volatile var dead = false
        val startNs = monotonicNanos()
        @Volatile var lastSeenNs = startNs
        val closed = CompletableDeferred<Unit>()
        private val terminated = AtomicBoolean(false)

        /** True while this is the current session and nothing has ended it. */
        fun live(): Boolean = !dead && active.get() === this

        /** Called once newWebSocket returned. If the session died meanwhile, drop the socket. */
        fun attach(socket: WebSocket) {
            ws = socket
            if (dead) socket.cancel()
        }

        /**
         * Idempotent end of the session: marks it dead (all later callbacks are ignored), wakes
         * the supervisor, and closes the socket. An established socket is closed with 1000 and
         * force-cancelled after [closeGraceMs] if the peer's close echo has not arrived; one that
         * never finished the upgrade is cancelled at once.
         */
        fun terminate(reason: String) {
            if (!terminated.compareAndSet(false, true)) return
            val wasOpen = open
            dead = true
            open = false
            closed.complete(Unit)
            val socket = ws ?: return // attach() cancels it when it sees `dead`
            if (wasOpen) {
                try {
                    socket.close(1000, reason.take(100))
                } catch (e: Exception) {
                    // close() on a socket that is already going away; the force cancel follows.
                }
                reaper.schedule(Runnable { socket.cancel() }, closeGraceMs, TimeUnit.MILLISECONDS)
            } else {
                socket.cancel()
            }
        }

        /** Callers have checked liveness (callbacks) or the generation (run loop). */
        fun markHealthy() {
            if (healthy) return
            healthy = true
            backoff.reset()
        }

        fun adoptRate(hz: Int?) {
            if (!live()) return
            if (Messages.isAdoptableRate(hz)) _rateHz.value = hz!!
        }

        val listener =
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!live()) return
                    lastSeenNs = monotonicNanos()
                    webSocket.send(Messages.hello(deviceName, _rateHz.value))
                    open = true // after hello is queued, so motion can never precede it
                    setState(gen, ConnectionState.Connected(host))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!live()) return
                    lastSeenNs = monotonicNanos() // any frame proves the link is alive
                    when (val m = Messages.parse(text)) {
                        is Incoming.Welcome -> {
                            if (!welcomed) {
                                welcomedAtNs = lastSeenNs
                                welcomed = true
                            }
                            adoptRate(m.rateHz)
                            val name = m.host
                            if (name != null && live()) {
                                setState(gen, ConnectionState.Connected(name))
                            }
                        }
                        is Incoming.Pong -> markHealthy()
                        is Incoming.Config -> adoptRate(m.rateHz)
                        null -> Unit
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (!live()) return
                    webSocket.close(1000, null)
                    // The supervisor wakes up, and terminate() force-cancels after the grace.
                    closed.complete(Unit)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!live()) return
                    closed.complete(Unit)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!live()) return
                    closed.complete(Unit)
                }
            }
    }

    companion object {
        /** Shared timer for the post-close force cancel; daemon so it never blocks JVM exit. */
        private val reaper: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "motiondots-close").apply { isDaemon = true }
            }
        }

        /**
         * No read timeout and no protocol-level ping: liveness is the app-level ping/pong. The
         * upgrade response is also read under readTimeout(0), which is why [MacConnection]
         * enforces its own handshake deadline.
         */
        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .connectTimeout(5, TimeUnit.SECONDS)
                .pingInterval(0, TimeUnit.MILLISECONDS)
                .build()
    }
}
