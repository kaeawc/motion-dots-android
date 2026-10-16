package dev.jasonpearson.motiondots.net

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MacConnectionTest {
    private lateinit var server: MockWebServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Server side of one socket: records frames, optionally answers pings, greets on open. */
    private class FakeMac(
        private val greeting: List<String> = emptyList(),
        private val answerPings: Boolean = true,
    ) : WebSocketListener() {
        val received = LinkedBlockingQueue<String>()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            greeting.forEach { webSocket.send(it) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            received.add(text)
            val o = Json.parseToJsonElement(text).jsonObject
            if (answerPings && o["type"]?.jsonPrimitive?.content == "ping") {
                webSocket.send("""{"type":"pong","t":${o["t"]!!.jsonPrimitive.content}}""")
            }
        }

        fun awaitType(type: String, seconds: Long = 5): String? {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            while (System.nanoTime() < deadline) {
                val msg = received.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (Json.parseToJsonElement(msg).jsonObject["type"]?.jsonPrimitive?.content == type) return msg
            }
            return null
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun accept(fake: FakeMac) {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(fake).build())
    }

    private fun connection(ping: Long = 100, timeout: Long = 400) =
        MacConnection(
            scope = scope,
            deviceName = "TestPhone",
            pingIntervalMs = ping,
            pongTimeoutMs = timeout,
            backoff = Backoff(initialMs = 50, maxMs = 100),
        )

    @Test
    fun helloIsTheFirstFrame() {
        val mac = FakeMac(listOf("""{"type":"welcome","v":1,"host":"TestHost","rateHz":50}"""))
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
        runBlocking {
            withTimeout(5_000) { conn.state.first { it == ConnectionState.Connected("TestHost") } }
        }
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
        runBlocking {
            withTimeout(5_000) { conn.state.first { it == ConnectionState.Connected("TestHost") } }
        }
        assertEquals(50, conn.rateHz.value)
        conn.disconnect()
    }

    @Test
    fun motionFramesFlowAfterConnect() {
        val mac = FakeMac(listOf("""{"type":"welcome","v":1,"host":"TestHost","rateHz":50}"""))
        accept(mac)
        server.start()
        val conn = connection()
        conn.connect(server.hostName, server.port)
        runBlocking {
            withTimeout(5_000) { conn.state.first { it == ConnectionState.Connected("TestHost") } }
        }
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
        val healthy = FakeMac(listOf("""{"type":"welcome","v":1,"host":"TestHost","rateHz":50}"""))
        accept(silent)
        accept(healthy)
        server.start()
        val conn = connection(ping = 50, timeout = 250)
        conn.connect(server.hostName, server.port)

        assertNotNull("second connection should receive hello", healthy.awaitType("hello", 10))
        assertNotNull("first connection should have been pinged", silent.awaitType("ping", 1))
        assertEquals(2, server.requestCount)
        runBlocking {
            withTimeout(5_000) { conn.state.first { it == ConnectionState.Connected("TestHost") } }
        }
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
}
