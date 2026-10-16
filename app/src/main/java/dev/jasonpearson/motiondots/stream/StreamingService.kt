package dev.jasonpearson.motiondots.stream

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service (dataSync) that owns the connection, the sensors and discovery so streaming
 * continues with the screen off. Control with [start] / [stop]; observe [StreamingController].
 *
 * Threading: every mutable field below is confined to the main thread. [scope] runs on
 * `Dispatchers.Main.immediate`, and anything that can fire from another thread (the coroutine
 * exception handler, the sensor callback is not one of them) hops to main through [onMain].
 *
 * Battery: sensors and the wake lock are only held while the connection is Connected. While the
 * Mac is unreachable they are released, and after [RETRY_BUDGET_LABEL] of failed retries the
 * service stops itself.
 */
class StreamingService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())

    // Main thread only from here down.
    private var scope: CoroutineScope? = null
    private var connection: MacConnection? = null
    private var sensors: MotionSensorSource? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var running = false
    private var engaged = false
    private var discoveryOn = false
    private var discoverJob: Job? = null
    private var refreshJob: Job? = null
    private var followWindowJob: Job? = null

    // Sensor thread only.
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
        if (!enterForeground()) return START_NOT_STICKY
        if (!running) beginStreaming()
        if (running && intent != null) resolveTarget(intent)
        return START_NOT_STICKY
    }

    /**
     * Android 15+: dataSync foreground services get a time budget (about 6 hours per day). Stop
     * cleanly when it ends; the user can start again by opening the app and tapping Start.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        StreamingController.setMessage(
            "Android's time limit for background data sync was reached, so streaming stopped. " +
                "Open the app and tap Start to continue."
        )
        shutdown()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    /** Returns false (after stopping the service with a UI message) if Android refuses. */
    private fun enterForeground(): Boolean {
        val notification = buildNotification(StreamingController.connection.value)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // The service type constant is API 29; below that the 2-argument form is used.
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            return true
        } catch (e: SecurityException) {
            Log.w(TAG, "startForeground refused", e)
            StreamingController.setMessage(
                "Android refused to start the streaming service. Check the app's permissions."
            )
        } catch (e: IllegalStateException) {
            // Includes ForegroundServiceStartNotAllowedException (API 31+, an IllegalStateException)
            // and ForegroundServiceTypeNotAllowedException.
            Log.w(TAG, "startForeground not allowed", e)
            StreamingController.setMessage(
                "Android didn't allow streaming to start from the background. " +
                    "Open the app and tap Start."
            )
        }
        shutdown()
        return false
    }

    private fun beginStreaming() {
        StreamingController.setMessage(null)
        val src = MotionSensorSource(this)
        if (!src.availability.usable) {
            failAndStop("This phone has no accelerometer.")
            return
        }
        running = true
        StreamingController.setStreaming(true)

        val handler = CoroutineExceptionHandler { _, t ->
            if (t is CancellationException) return@CoroutineExceptionHandler
            Log.e(TAG, "Uncaught exception in service scope", t)
            val what = t.message ?: t.javaClass.simpleName
            failAndStop("Streaming stopped after an unexpected error: $what")
        }
        // A fresh job per run so a previous run's cancellation can never leak into this one.
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + handler)
        scope = s

        val conn =
            MacConnection(
                scope = s,
                deviceName = Build.MODEL ?: "Android",
                monotonicNanos = SystemClock::elapsedRealtimeNanos,
            )
        connection = conn
        sensors = src

        s.launch {
            conn.state.collect { st ->
                StreamingController.setConnection(st)
                notifyState(st)
                when (st) {
                    is ConnectionState.Connected -> {
                        followWindowJob?.cancel()
                        setDiscovery(false)
                        engage(conn, src)
                    }
                    is ConnectionState.Reconnecting -> {
                        // Not connected: stop sampling and release the wake lock.
                        disengage()
                        if (st.attempt == 2) openFollowWindow()
                    }
                    is ConnectionState.Connecting -> disengage()
                    ConnectionState.Idle -> Unit
                }
            }
        }
        s.launch {
            conn.rateHz.collect {
                StreamingController.setRequestedHz(it)
                src.setRate(it)
            }
        }
        s.launch { conn.dropped.collect { StreamingController.setDropped(it) } }
        s.launch { src.achievedHz.collect { StreamingController.setAchievedHz(it) } }
        s.launch {
            conn.gaveUp.collect { gaveUp ->
                if (gaveUp) {
                    failAndStop(
                        "Couldn't reach your Mac for $RETRY_BUDGET_LABEL. Tap Start to try again."
                    )
                }
            }
        }
        s.launch {
            discovery.problem.collect { problem ->
                if (problem != null) StreamingController.setMessage(problem)
            }
        }
        s.launch {
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
    }

    /** Connected: hold the wake lock and sample the sensors. */
    private fun engage(conn: MacConnection, src: MotionSensorSource) {
        if (engaged || !running) return
        engaged = true
        acquireWakeLock()
        val started =
            src.start(conn.rateHz.value) { t, a, g ->
                conn.sendMotion(t, a, g)
                publishDebug(a)
            }
        if (!started) {
            failAndStop("This phone has no accelerometer.")
            return
        }
        refreshJob?.cancel()
        refreshJob =
            scope?.launch {
                while (true) {
                    delay(WAKELOCK_REFRESH_MS)
                    acquireWakeLock()
                }
            }
    }

    /** Not connected: unregister the sensors and release the wake lock. */
    private fun disengage() {
        if (!engaged) return
        engaged = false
        refreshJob?.cancel()
        refreshJob = null
        sensors?.stop()
        releaseWakeLock()
        StreamingController.setAchievedHz(0f)
    }

    /**
     * Back-off lookup: at the 2nd reconnect attempt run NSD once for a short window so a Mac that
     * moved can be followed by name. Beyond that discovery stays off (multicast lock and radio).
     */
    private fun openFollowWindow() {
        setDiscovery(true)
        followWindowJob?.cancel()
        followWindowJob =
            scope?.launch {
                delay(FOLLOW_WINDOW_MS)
                if (discoverJob?.isActive != true) setDiscovery(false)
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
        if (discoverJob?.isActive == true) return // dedupe concurrent lookups
        discoverJob = scope?.launch { discoverSingle() }
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
            setDiscovery(false) // found it; only the back-off follow window turns it on again
        } else {
            failAndStop("Found ${list.size} Macs. Pick one from the list.")
        }
    }

    /** Safe from any thread. */
    private fun failAndStop(message: String) =
        onMain {
            StreamingController.setMessage(message)
            shutdownOnMain()
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
        // Timed acquire so a leaked lock cannot outlive us; refreshed by refreshJob.
        lock.acquire(WAKELOCK_TIMEOUT_MS)
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /** Safe from any thread. */
    private fun shutdown() = onMain { shutdownOnMain() }

    private fun shutdownOnMain() {
        cleanup()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /** Idempotent. Main thread. */
    private fun cleanup() {
        val wasRunning = running
        running = false // first, so notifyState() from late collector emissions is a no-op
        connection?.disconnect()
        connection = null
        disengage()
        sensors?.stop()
        sensors = null
        if (discoveryOn) {
            discoveryOn = false
            discovery.stop()
        }
        releaseWakeLock()
        scope?.cancel()
        scope = null
        discoverJob = null
        refreshJob = null
        followWindowJob = null
        engaged = false
        if (wasRunning) StreamingController.resetRuntime()
        // Last, so nothing above can re-post it.
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun notifyState(state: ConnectionState) {
        if (!running) return
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(state))
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
        private const val FOLLOW_WINDOW_MS = 15_000L
        private const val TAG = "StreamingService"
        private const val RETRY_BUDGET_LABEL = "5 minutes"

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
