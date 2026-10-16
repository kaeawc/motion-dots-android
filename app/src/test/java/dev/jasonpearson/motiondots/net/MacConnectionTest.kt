package dev.jasonpearson.motiondots.net

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MacConnectionTest {
    private lateinit var server: MockWebServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val silentServers = CopyOnWriteArrayList<SilentServer>()

    private val welcome = """{"type":"welcome","v":1,"host":"TestHost","rateHz":50}"""

    /** Server side of one socket: records frames, optionally answers pings, greets on open. */
    private class FakeMac(
        private val greeting: List<String> = emptyList(),
        private val answerPings: Boolean = true,
        /** If > 0, sends a harmless config frame this often (any frame proves liveness). */
        private val chatterMs: Long = 0,
        /** Closes the socket with 1000 right after the greeting. */
        private val closeAfterGreeting: Boolean = false,
    ) : WebSocketListener() {
        val received = LinkedBlockingQueue<String>()
        val closingCode = CompletableFuture<Int>()

        @Volatile private var stopped = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            greeting.forEach { webSocket.send(it) }
            if (closeAfterGreeting) webSocket.close(1000, "test")
            if (chatterMs > 0) {
                Thread {
                        while (!stopped && webSocket.send("""{"type":"config","rateHz":50}""")) {
                            Thread.sleep(chatterMs)
                        }
                    }
                    .apply { isDaemon = true }
                    .start()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            received.add(text)
            val o = Json.parseToJsonElement(text).jsonObject
            if (answerPings && o["type"]?.jsonPrimitive?.content == "ping") {
                webSocket.send("""{"type":"pong","t":${o["t"]!!.jsonPrimitive.content}}""")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            stopped = true
            closingCode.complete(code)
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            stopped = true
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            stopped = true
        }

        fun awaitType(type: String, seconds: Long = 5): String? {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            while (System.nanoTime() < deadline) {
                val msg = received.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (Json.parseToJsonElement(msg).jsonObject["type"]?.jsonPrimitive?.content == type)
                    return msg
            }
            return null
        }
    }

    /** Accepts TCP connections and never answers: the WebSocket upgrade can never complete. */
    private class SilentServer {
        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val open = CopyOnWriteArrayList<Socket>()
        val accepted = AtomicInteger(0)
        val port: Int
            get() = socket.localPort

        init {
            Thread {
                    try {
                        while (true) {
                            open.add(socket.accept())
                            accepted.incrementAndGet()
                        }
                    } catch (e: Exception) {
                        // closed
                    }
                }
                .apply { isDaemon = true }
                .start()
        }

        fun close() {
            runCatching { socket.close() }
            open.forEach { runCatching { it.close() } }
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        scope.cancel()
        silentServers.forEach { it.close() }
        server.close()
    }

    private fun accept(fake: FakeMac) {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(fake).build())
    }

    private fun silentServer() = SilentServer().also { silentServers.add(it) }

    private fun awaitUntil(seconds: Long = 5, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private fun awaitConnected(conn: MacConnection) {
        runBlocking {
            withTimeout(5_000) { conn.state.first { it == ConnectionState.Connected("TestHost") } }
        }
    }

    private fun connection(
        ping: Long = 100,
        timeout: Long = 400,
        backoff: Backoff = Backoff(initialMs = 50, maxMs = 100, jitter = 0.0),
        clock: () -> Long = System::nanoTime,
        handshakeMs: Long = 8_000,
        healthyAfterMs: Long = 10_000,
        budgetMs: Long = 5L * 60_000L,
    ) =
        MacConnection(
            scope = scope,
            deviceName = "TestPhone",
            pingIntervalMs = ping,
            pongTimeoutMs = timeout,
            backoff = backoff,
            monotonicNanos = clock,
            handshakeTimeoutMs = handshakeMs,
            healthyAfterMs = healthyAfterMs,
            retryBudgetMs = budgetMs,
        )

    @Test
    fun helloIsTheFirstFrame() {
        val mac = FakeMac(listOf(welcome))
        accept(mac)
        server.start()
        val conn = connection()
        conn.connect(server.hostName, server.port)

        val first = mac.received.poll(5, TimeUnit.SECONDS)
        assertNotNull(first)
        val o = Json.parseToJsonElement(first!!).jsonObject
        assertEquals("hello", o["type"]!!.jsonPrimitive.content)
        assertEquals("android", o["platform"]!!.jsonPrimitive.content)
        assertEquals("TestPhone", o["name"]!!.jsonPrimitive.content)
        awaitConnected(conn)
        conn.disconnect()
        assertEquals(ConnectionState.Idle, conn.state.value)
    }

    @Test
    fun welcomeAndConfigRateAdoption() {
        accept(
            FakeMac(
                listOf(
                    """{"type":"welcome","v":1,"host":"TestHost","rateHz":90}""",
                    """{"type":"config","rateHz":25,"unknown":true}""",
                )
            )
        )
        server.start()
        val conn = connection()
        conn.connect(server.hostName, server.port)
        runBlocking { withTimeout(5_000) { conn.rateHz.first { it == 25 } } }
        conn.disconnect()
    }

    @Test
    fun outOfRangeWelcomeRateIsIgnored() {
        accept(FakeMac(listOf("""{"type":"welcome","v":1,"host":"TestHost","rateHz":90}""")))
        server.start()
        val conn = connection()
        conn.connect(server.hostName, server.port)
        awaitConnected(conn)
        assertEquals(50, conn.rateHz.value)
        conn.disconnect()
    }

    @Test
    fun motionFramesFlowAfterConnect() {
        val mac = FakeMac(listOf(welcome))
        accept(mac)
        server.start()
        val conn = connection()
        conn.connect(server.hostName, server.port)
        awaitConnected(conn)
        conn.sendMotion(1.5, floatArrayOf(0.1f, 0.2f, 0.3f), floatArrayOf(0f, 0f, 9.81f))
        val motion = mac.awaitType("motion")
        assertNotNull(motion)
        val o = Json.parseToJsonElement(motion!!).jsonObject
        assertEquals(3, o["a"]!!.let { (it as kotlinx.serialization.json.JsonArray).size })
        conn.disconnect()
    }

    @Test
    fun missingPongTriggersReconnectWithFreshHello() {
        val silent = FakeMac(answerPings = false)
        val healthy = FakeMac(listOf(welcome))
        accept(silent)
        accept(healthy)
        server.start()
        val conn = connection(ping = 50, timeout = 250)
        conn.connect(server.hostName, server.port)

        assertNotNull("second connection should receive hello", healthy.awaitType("hello", 10))
        assertNotNull("first connection should have been pinged", silent.awaitType("ping", 1))
        assertEquals(2, server.requestCount)
        awaitConnected(conn)
        conn.disconnect()
    }

    @Test
    fun refusedConnectionRetriesWithReconnectingState() {
        server.start()
        val port = server.port
        server.close() // nothing listens any more
        val conn = connection()
        conn.connect("127.0.0.1", port)
        val s = runBlocking {
            withTimeout(5_000) { conn.state.first { it is ConnectionState.Reconnecting } }
        }
        assertTrue(s is ConnectionState.Reconnecting)
        conn.disconnect()
        server = MockWebServer() // so tearDown can close it again
    }

    // ---- B1: handshake deadline ----

    @Test
    fun handshakeDeadlineTearsDownAServerThatNeverUpgrades() {
        val silent = silentServer()
        val conn = connection(handshakeMs = 300)
        conn.connect("127.0.0.1", silent.port)
        // The first socket is accepted and stalls; after the deadline a second attempt appears.
        assertTrue("second attempt expected", awaitUntil(8) { silent.accepted.get() >= 2 })
        conn.disconnect()
    }

    // ---- B3: stale callbacks ----

    @Test
    fun supersededSessionNeverChangesState() {
        val silent = silentServer()
        accept(FakeMac(listOf(welcome)))
        server.start()
        val seen = CopyOnWriteArrayList<ConnectionState>()
        val conn = connection(handshakeMs = 200)
        val collector = scope.launch { conn.state.collect { seen.add(it) } }

        conn.connect("127.0.0.1", silent.port) // first connect: stuck mid-handshake
        assertTrue(awaitUntil { silent.accepted.get() >= 1 })
        conn.connect(server.hostName, server.port) // second connect supersedes it
        awaitConnected(conn)
        // Give the first session's deadline and socket teardown time to fire callbacks.
        Thread.sleep(700)
        assertEquals(ConnectionState.Connected("TestHost"), conn.state.value)
        assertFalse(
            "no Reconnecting from the superseded run: $seen",
            seen.any { it is ConnectionState.Reconnecting },
        )
        assertEquals(1, server.requestCount)
        collector.cancel()
        conn.disconnect()
    }

    @Test
    fun disconnectSendsCloseCode1000() {
        val mac = FakeMac(listOf(welcome))
        accept(mac)
        server.start()
        val conn = connection()
        conn.connect(server.hostName, server.port)
        awaitConnected(conn)
        conn.disconnect()
        assertEquals(ConnectionState.Idle, conn.state.value)
        assertEquals(1000, mac.closingCode.get(5, TimeUnit.SECONDS))
    }

    // ---- B10: liveness by any frame ----

    @Test
    fun anyInboundFrameKeepsTheLinkAliveWithoutPongs() {
        val mac = FakeMac(greeting = listOf(welcome), answerPings = false, chatterMs = 50)
        accept(mac)
        server.start()
        val conn = connection(ping = 50, timeout = 300)
        conn.connect(server.hostName, server.port)
        awaitConnected(conn)
        Thread.sleep(1_200) // 4x the pong timeout; a pong-only check would have reconnected
        assertEquals("no reconnect expected", 1, server.requestCount)
        assertEquals(ConnectionState.Connected("TestHost"), conn.state.value)
        conn.disconnect()
    }

    // ---- B5: backoff reset policy ----

    @Test
    fun quickWelcomedDropDoesNotResetBackoff() {
        repeat(3) {
            accept(
                FakeMac(listOf(welcome), answerPings = false, closeAfterGreeting = true)
            )
        }
        server.start()
        val backoff = Backoff(initialMs = 50, maxMs = 100, jitter = 0.0)
        val conn = connection(backoff = backoff, ping = 1_000, timeout = 5_000)
        conn.connect(server.hostName, server.port)
        // Each session is welcomed but lasts milliseconds and never gets a pong: no reset.
        assertTrue(
            "backoff must keep growing, was ${backoff.attempts()}",
            awaitUntil(8) { backoff.attempts() >= 3 },
        )
        conn.disconnect()
    }

    @Test
    fun firstPongResetsBackoff() {
        accept(FakeMac(listOf(welcome), answerPings = false, closeAfterGreeting = true))
        val healthy = FakeMac(listOf(welcome))
        accept(healthy)
        server.start()
        val backoff = Backoff(initialMs = 50, maxMs = 100, jitter = 0.0)
        val conn = connection(backoff = backoff, ping = 50, timeout = 2_000)
        conn.connect(server.hostName, server.port)
        assertNotNull(healthy.awaitType("ping", 8)) // second session is up and being answered
        assertTrue(
            "first pong should reset, was ${backoff.attempts()}",
            awaitUntil { backoff.attempts() == 0 },
        )
        conn.disconnect()
    }

    @Test
    fun welcomedSessionOpenLongEnoughResetsBackoffWithoutPong() {
        accept(FakeMac(listOf(welcome), answerPings = false, closeAfterGreeting = true))
        val steady = FakeMac(listOf(welcome), answerPings = false, chatterMs = 50)
        accept(steady)
        server.start()
        val backoff = Backoff(initialMs = 50, maxMs = 100, jitter = 0.0)
        val conn = connection(backoff = backoff, ping = 50, timeout = 2_000, healthyAfterMs = 300)
        conn.connect(server.hostName, server.port)
        assertNotNull(steady.awaitType("hello", 8))
        assertTrue(
            "healthy session should reset, was ${backoff.attempts()}",
            awaitUntil { backoff.attempts() == 0 },
        )
        conn.disconnect()
    }

    // ---- B2: retry budget ----

    @Test
    fun givesUpAfterRetryBudgetOfFailedAttempts() {
        server.start()
        val port = server.port
        server.close() // nothing listens any more
        val clock = AtomicLong(1_000_000_000L)
        val conn = connection(clock = { clock.get() }, budgetMs = 5L * 60_000L)
        conn.connect("127.0.0.1", port)
        runBlocking {
            withTimeout(5_000) { conn.state.first { it is ConnectionState.Reconnecting } }
        }
        assertFalse(conn.gaveUp.value)
        clock.addAndGet(TimeUnit.MINUTES.toNanos(4)) // still inside the budget
        Thread.sleep(300)
        assertFalse(conn.gaveUp.value)
        clock.addAndGet(TimeUnit.MINUTES.toNanos(2)) // now past 5 minutes without a Connected
        runBlocking { withTimeout(5_000) { conn.gaveUp.first { it } } }
        assertEquals(ConnectionState.Idle, conn.state.value)
        server = MockWebServer() // so tearDown can close it again
    }

    @Test
    fun connectClearsGaveUp() {
        server.start()
        val port = server.port
        server.close()
        val clock = AtomicLong(0L)
        val conn = connection(clock = { clock.get() }, budgetMs = 1_000L)
        conn.connect("127.0.0.1", port)
        runBlocking {
            withTimeout(5_000) { conn.state.first { it is ConnectionState.Reconnecting } }
        }
        clock.addAndGet(TimeUnit.SECONDS.toNanos(10))
        runBlocking { withTimeout(5_000) { conn.gaveUp.first { it } } }
        conn.connect("127.0.0.1", port)
        assertFalse(conn.gaveUp.value)
        conn.disconnect()
        server = MockWebServer()
    }
}
