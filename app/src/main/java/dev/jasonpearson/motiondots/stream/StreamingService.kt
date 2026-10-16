package dev.jasonpearson.motiondots.stream

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.jasonpearson.motiondots.App
import dev.jasonpearson.motiondots.MainActivity
import dev.jasonpearson.motiondots.net.ConnectionState
import dev.jasonpearson.motiondots.net.DiscoveredMac
import dev.jasonpearson.motiondots.net.MacConnection
import dev.jasonpearson.motiondots.net.MacDiscovery
import dev.jasonpearson.motiondots.sensors.MotionSensorSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service (dataSync) that owns the connection, the sensors and discovery so streaming
 * continues with the screen off. Control with [start] / [stop]; observe [StreamingController].
 */
class StreamingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var connection: MacConnection? = null
    private var sensors: MotionSensorSource? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var running = false
    private var discoveryOn = false
    private var lastDebugMs = 0L
    private lateinit var prefs: MacPrefs
    private val discovery by lazy { MacDiscovery(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = MacPrefs(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(StreamingController.connection.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (!running) beginStreaming()
        if (intent != null) resolveTarget(intent)
        return START_NOT_STICKY
    }

    /** Android 15+: dataSync foreground services get a time budget. Stop cleanly when it ends. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        StreamingController.setMessage("Android stopped the background stream (time limit).")
        shutdown()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun beginStreaming() {
        running = true
        StreamingController.setStreaming(true)
        StreamingController.setMessage(null)
        acquireWakeLock()

        val conn =
            MacConnection(
                scope = scope,
                deviceName = Build.MODEL ?: "Android",
                monotonicNanos = SystemClock::elapsedRealtimeNanos,
            )
        connection = conn
        val src = MotionSensorSource(this)
        sensors = src

        val started =
            src.start(conn.rateHz.value) { t, a, g ->
                conn.sendMotion(t, a, g)
                publishDebug(a)
            }
        if (!started) StreamingController.setMessage("This phone has no accelerometer.")

        scope.launch {
            conn.state.collect { s ->
                StreamingController.setConnection(s)
                notifyState(s)
                when (s) {
                    is ConnectionState.Reconnecting -> if (s.attempt >= 2) setDiscovery(true)
                    is ConnectionState.Connected -> setDiscovery(false)
                    else -> Unit
                }
            }
        }
        scope.launch {
            conn.rateHz.collect {
                StreamingController.setRequestedHz(it)
                src.setRate(it)
            }
        }
        scope.launch { conn.dropped.collect { StreamingController.setDropped(it) } }
        scope.launch { src.achievedHz.collect { StreamingController.setAchievedHz(it) } }
        scope.launch {
            // Mac may have moved (DHCP): follow it by name while we are failing to reconnect.
            discovery.macs.collect { list ->
                val target = StreamingController.target.value ?: return@collect
                if (StreamingController.connection.value !is ConnectionState.Reconnecting) {
                    return@collect
                }
                val moved = list.firstOrNull {
                    it.name == target.name && (it.host != target.host || it.port != target.port)
                }
                if (moved != null) connectTo(moved)
            }
        }
        scope.launch {
            while (true) {
                delay(WAKELOCK_REFRESH_MS)
                acquireWakeLock()
            }
        }
    }

    private fun resolveTarget(intent: Intent) {
        val host = intent.getStringExtra(EXTRA_HOST)
        val port = intent.getIntExtra(EXTRA_PORT, -1)
        if (host != null && port > 0) {
            connectTo(DiscoveredMac(intent.getStringExtra(EXTRA_NAME) ?: host, host, port))
            return
        }
        // "auto": last used Mac, else the only discovered Mac.
        val last = prefs.load()
        if (last != null) {
            connectTo(last)
            return
        }
        scope.launch { discoverSingle() }
    }

    private suspend fun discoverSingle() {
        setDiscovery(true)
        val any = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) { discovery.macs.first { it.isNotEmpty() } }
        if (any == null) {
            failAndStop("No Mac found. Is MotionDots running on your Mac? Try entering host:port.")
            return
        }
        delay(1_500L) // let other Macs show up before deciding
        val list = discovery.macs.value
        if (list.size == 1) {
            connectTo(list[0])
        } else {
            failAndStop("Found ${list.size} Macs. Pick one from the list.")
        }
    }

    private fun failAndStop(message: String) {
        StreamingController.setMessage(message)
        shutdown()
    }

    private fun connectTo(mac: DiscoveredMac) {
        StreamingController.setTarget(mac)
        prefs.save(mac)
        connection?.connect(mac.host, mac.port)
    }

    private fun setDiscovery(on: Boolean) {
        if (on == discoveryOn) return
        discoveryOn = on
        if (on) discovery.start() else discovery.stop()
    }

    private fun publishDebug(a: FloatArray) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDebugMs < 100L) return
        lastDebugMs = now
        StreamingController.setLinearAccel(floatArrayOf(a[0], a[1], a[2]))
    }

    private fun acquireWakeLock() {
        val lock =
            wakeLock
                ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "motiondots:stream")
                    .also {
                        it.setReferenceCounted(false)
                        wakeLock = it
                    }
        // Timed acquire so a leaked lock cannot outlive us; refreshed by the loop in scope.
        lock.acquire(WAKELOCK_TIMEOUT_MS)
    }

    private fun shutdown() {
        cleanup()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        connection?.disconnect()
        connection = null
        sensors?.stop()
        sensors = null
        if (discoveryOn) {
            discoveryOn = false
            discovery.stop()
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        scope.cancel()
        running = false
        StreamingController.resetRuntime()
    }

    private fun notifyState(state: ConnectionState) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: ConnectionState): Notification {
        val text =
            when (state) {
                ConnectionState.Idle -> "Starting"
                is ConnectionState.Connecting -> "Connecting to ${state.host}"
                is ConnectionState.Connected ->
                    "Streaming to ${state.hostName} at ${StreamingController.requestedHz.value} Hz"
                is ConnectionState.Reconnecting -> "Reconnecting (attempt ${state.attempt})"
            }
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, StreamingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat.Builder(this, App.STREAM_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("MotionDots")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()
    }

    companion object {
        const val ACTION_START = "dev.jasonpearson.motiondots.action.START"
        const val ACTION_STOP = "dev.jasonpearson.motiondots.action.STOP"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_NAME = "name"
        private const val NOTIFICATION_ID = 1
        private const val WAKELOCK_TIMEOUT_MS = 60L * 60_000L
        private const val WAKELOCK_REFRESH_MS = 30L * 60_000L
        private const val DISCOVERY_TIMEOUT_MS = 15_000L

        /** Start streaming to an explicit Mac. */
        fun start(context: Context, mac: DiscoveredMac) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, StreamingService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_HOST, mac.host)
                    .putExtra(EXTRA_PORT, mac.port)
                    .putExtra(EXTRA_NAME, mac.name),
            )
        }

        /** Start streaming to the last used Mac, or the only Mac on the network. */
        fun startAuto(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, StreamingService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, StreamingService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
