package dev.jasonpearson.motiondots.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Registers linear acceleration + gravity on a dedicated HandlerThread and emits one combined
 * sample per linear-acceleration event (rate limited). Falls back to the raw accelerometer when
 * linear acceleration is missing. The arrays passed to the callback are reused: copy if kept.
 */
class MotionSensorSource(context: Context) {
    data class Availability(val linear: Boolean, val gravity: Boolean, val accelerometer: Boolean) {
        val usable: Boolean
            get() = linear || accelerometer
    }

    private val sm = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linear = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gravitySensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    val availability = Availability(linear != null, gravitySensor != null, accel != null)

    private val _achievedHz = MutableStateFlow(0f)
    /** Measured emitted samples per second, updated about once a second. */
    val achievedHz: StateFlow<Float> = _achievedHz.asStateFlow()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var callback: ((Double, FloatArray, FloatArray) -> Unit)? = null
    private var rateHz = 50
    private val limiter = RateLimiter(50)
    private val lowPass = GravityLowPass()

    // Touched only on the sensor thread.
    private val gravity = floatArrayOf(0f, 0f, 9.81f)
    private val outA = FloatArray(3)
    private var windowStartNs = 0L
    private var windowCount = 0

    /** Returns false if no usable acceleration sensor exists. */
    @Synchronized
    fun start(rateHz: Int, onSample: (t: Double, a: FloatArray, g: FloatArray) -> Unit): Boolean {
        if (!availability.usable) return false
        stop()
        this.rateHz = rateHz
        callback = onSample
        limiter.setRate(rateHz)
        lowPass.reset()
        windowStartNs = 0L
        windowCount = 0
        val t = HandlerThread("motion-sensors").also { it.start() }
        thread = t
        handler = Handler(t.looper)
        register()
        return true
    }

    /** Changes the sampling rate without restarting the thread. */
    @Synchronized
    fun setRate(newRateHz: Int) {
        if (callback == null || newRateHz == rateHz) return
        rateHz = newRateHz
        sm.unregisterListener(listener)
        limiter.setRate(newRateHz)
        register()
    }

    @Synchronized
    fun stop() {
        sm.unregisterListener(listener)
        thread?.quitSafely()
        thread = null
        handler = null
        callback = null
        _achievedHz.value = 0f
    }

    private fun register() {
        val h = handler ?: return
        val period = 1_000_000 / rateHz.coerceAtLeast(1)
        if (linear != null) {
            sm.registerListener(listener, linear, period, 0, h)
            if (gravitySensor != null) {
                sm.registerListener(listener, gravitySensor, period, 0, h)
            } else if (accel != null) {
                // No gravity sensor: estimate gravity from the raw accelerometer.
                sm.registerListener(listener, accel, period, 0, h)
            }
        } else if (accel != null) {
            sm.registerListener(listener, accel, period, 0, h)
            if (gravitySensor != null) sm.registerListener(listener, gravitySensor, period, 0, h)
        }
    }

    private val listener =
        object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

            override fun onSensorChanged(event: SensorEvent) {
                val v = event.values
                when (event.sensor.type) {
                    Sensor.TYPE_GRAVITY -> {
                        gravity[0] = v[0]
                        gravity[1] = v[1]
                        gravity[2] = v[2]
                    }
                    Sensor.TYPE_LINEAR_ACCELERATION -> emit(event.timestamp, v)
                    Sensor.TYPE_ACCELEROMETER -> {
                        if (gravitySensor == null) {
                            val est = lowPass.update(v, event.timestamp)
                            gravity[0] = est[0]
                            gravity[1] = est[1]
                            gravity[2] = est[2]
                        }
                        if (linear == null) {
                            AccelFallback.linearFrom(v, gravity, outA)
                            emit(event.timestamp, outA)
                        }
                    }
                }
            }
        }

    private fun emit(timestampNs: Long, a: FloatArray) {
        if (!limiter.accept(timestampNs)) return
        callback?.invoke(timestampNs / 1e9, a, gravity)
        if (windowStartNs == 0L) windowStartNs = timestampNs
        windowCount++
        val elapsed = timestampNs - windowStartNs
        if (elapsed >= 1_000_000_000L) {
            _achievedHz.value = windowCount * 1e9f / elapsed
            windowStartNs = timestampNs
            windowCount = 0
        }
    }
}
