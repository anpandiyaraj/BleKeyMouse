package com.blekeymouse.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * IMU Sensor-driven "Air Mouse" provider component.
 *
 * Pipeline:
 *  1. Sensor events are delivered on a dedicated high-priority [HandlerThread] and processed
 *     synchronously, in order. (Previously every event was launched as a coroutine on the
 *     multi-threaded Default pool: events were processed out of order and concurrently,
 *     corrupting the filter state, and the recycled [SensorEvent] object could be overwritten
 *     before it was read — causing jitter and random jumps.)
 *  2. Calibrated [Sensor.TYPE_GYROSCOPE] is preferred. The uncalibrated gyro (used before)
 *     includes the bias drift, which made the cursor creep while the phone was still; if it
 *     is the only option, its bias estimate (values[3..5]) is subtracted.
 *  3. Heading-independent projection: only the *gravity* direction from the rotation vector
 *     is used.
 *       • horizontal (yaw)   = ω · up              — turning left/right about gravity
 *       • vertical   (pitch) = ω · rightHorizontal — tilting the top edge up/down, where
 *                              rightHorizontal is the phone's X axis flattened to the horizon
 *     The old code used the world X (East) axis for pitch, so vertical motion shrank to zero
 *     when the user faced East/West, and broke completely with GAME_ROTATION_VECTOR whose
 *     heading is arbitrary.
 *  4. Static noise gate + speed-adaptive low-pass filter remove hand tremor without lag.
 *  5. Click stabilisation: [suppressMotion] freezes the cursor for a short window around a
 *     button press so the act of tapping the screen doesn't drag the pointer off target.
 *  6. Sub-pixel accumulator guarantees 1:1 motion precision.
 */
class AirMouseProvider(
    context: Context,
    private val onDisplacementCalculated: (dx: Int, dy: Int) -> Unit,
) : SensorEventListener {

    companion object {
        private const val TAG = "AirMouseProvider"
        private const val RAD_TO_DEG = (180.0 / Math.PI).toFloat()

        /** Threshold in rad/s below which static gyro noise is gated to zero. */
        private const val GYRO_STATIC_THRESHOLD = 0.02f

        /** 100 Hz — smooth pointer without needing HIGH_SAMPLING_RATE_SENSORS. */
        private const val SAMPLING_PERIOD_US = 10_000

        const val DEFAULT_SENSITIVITY = 15f
        const val MIN_SENSITIVITY = 4f
        const val MAX_SENSITIVITY = 40f
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
    private val rotationVectorSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gravitySensor: Sensor? =
        if (rotationVectorSensor == null) sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) else null

    private var sensorThread: HandlerThread? = null

    private val _isTracking = MutableStateFlow(value = false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    /** Cursor speed scaling factor (mouse counts per degree of wrist rotation). */
    @Volatile
    var sensitivity: Float = DEFAULT_SENSITIVITY
        set(value) { field = value.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY) }

    /** Threshold in degrees below which micro-movements are ignored. */
    @Volatile
    var deadzoneThreshold: Float = 0.01f

    /** Cursor is frozen until this uptime (ms) — see [suppressMotion]. */
    @Volatile
    private var suppressUntilMs = 0L

    // ── State below is only touched on the sensor thread ──────────────────
    private val rotationMatrix = FloatArray(9)
    /** World "up" axis expressed in device coordinates (normalised). */
    private val up = floatArrayOf(0f, 0f, 1f)
    private var hasGravity = false

    private var filteredDx = 0f
    private var filteredDy = 0f
    private var accumX = 0f
    private var accumY = 0f
    private var lastTimestampNanos = 0L

    /**
     * Starts tracking IMU sensor events.
     */
    @Synchronized
    fun startTracking() {
        if (_isTracking.value) return
        val gyro = gyroSensor ?: run {
            Log.e(TAG, "Gyroscope sensor unavailable — Air Mouse cannot start")
            return
        }

        val thread = HandlerThread("AirMouseSensor", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        sensorThread = thread
        val handler = Handler(thread.looper)

        handler.post { resetState() }

        val gyroRegistered = sensorManager.registerListener(this, gyro, SAMPLING_PERIOD_US, handler)
        val orientationSensor = rotationVectorSensor ?: gravitySensor
        val orientationRegistered = orientationSensor?.let {
            sensorManager.registerListener(this, it, SAMPLING_PERIOD_US, handler)
        } ?: false

        if (!gyroRegistered) {
            Log.e(TAG, "Failed to register gyroscope listener")
            sensorManager.unregisterListener(this)
            thread.quitSafely()
            sensorThread = null
            return
        }

        _isTracking.value = true
        Log.i(TAG, "Air Mouse tracking started (gyro=${gyro.name}, orientation=${orientationSensor?.name}, registered=$orientationRegistered)")
    }

    /**
     * Stops sensor tracking and unregisters listeners to conserve battery.
     */
    @Synchronized
    fun stopTracking() {
        if (!_isTracking.value) return
        sensorManager.unregisterListener(this)
        sensorThread?.quitSafely()
        sensorThread = null
        _isTracking.value = false
        Log.i(TAG, "Air Mouse tracking stopped")
    }

    /**
     * Freezes the cursor for [durationMs]. Call on mouse button down/up so that the physical
     * motion of tapping the phone doesn't move the pointer off the click target.
     */
    fun suppressMotion(durationMs: Long = 180L) {
        suppressUntilMs = SystemClock.uptimeMillis() + durationMs
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Runs on the dedicated sensor HandlerThread, in delivery order.
        if (!_isTracking.value) return
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                // Row 3 of R = world Z (up) expressed in device coordinates
                setUp(rotationMatrix[6], rotationMatrix[7], rotationMatrix[8])
            }
            Sensor.TYPE_GRAVITY -> setUp(event.values[0], event.values[1], event.values[2])
            Sensor.TYPE_GYROSCOPE -> processGyroscope(event.timestamp, event.values[0], event.values[1], event.values[2])
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> processGyroscope(
                event.timestamp,
                event.values[0] - event.values[3],
                event.values[1] - event.values[4],
                event.values[2] - event.values[5]
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    private fun setUp(x: Float, y: Float, z: Float) {
        val n = sqrt(x * x + y * y + z * z)
        if (n < 1e-3f) return
        up[0] = x / n; up[1] = y / n; up[2] = z / n
        hasGravity = true
    }

    private fun processGyroscope(timestamp: Long, gx: Float, gy: Float, gz: Float) {
        if (lastTimestampNanos == 0L) {
            lastTimestampNanos = timestamp
            return
        }
        val dt = (timestamp - lastTimestampNanos) * 1e-9f
        lastTimestampNanos = timestamp

        // Clamp dt against unexpected OS sensor pauses
        if (dt <= 0f || dt > 0.1f) return

        // Click stabilisation window — drop motion and reset filter so there's no catch-up jump
        if (SystemClock.uptimeMillis() < suppressUntilMs) {
            filteredDx = 0f; filteredDy = 0f; accumX = 0f; accumY = 0f
            return
        }

        // 1. Static noise gate: filter out resting hand tremors
        if (sqrt(gx * gx + gy * gy + gz * gz) < GYRO_STATIC_THRESHOLD) {
            filterAndDispatch(0f, 0f)
            return
        }

        val yawRate: Float
        val pitchRate: Float
        if (hasGravity) {
            // 2. Heading-independent projection
            yawRate = gx * up[0] + gy * up[1] + gz * up[2]

            // Phone X axis flattened onto the horizontal plane: X - (X·up)·up
            val rx = 1f - up[0] * up[0]
            val ry = -up[0] * up[1]
            val rz = -up[0] * up[2]
            val rn = sqrt(rx * rx + ry * ry + rz * rz)
            pitchRate = if (rn > 0.2f) {
                (gx * rx + gy * ry + gz * rz) / rn
            } else {
                gx // phone rolled ~90° (X axis vertical) — fall back to raw pitch
            }
        } else {
            // Fallback: phone held upright, top edge pointing at the screen
            yawRate = gz
            pitchRate = gx
        }

        // Turning left (positive yaw, CCW from above) → cursor left; tilting top up → cursor up
        val rawDx = -yawRate * dt * RAD_TO_DEG
        val rawDy = -pitchRate * dt * RAD_TO_DEG
        filterAndDispatch(rawDx, rawDy)
    }

    private fun filterAndDispatch(rawDx: Float, rawDy: Float) {
        val speed = sqrt(rawDx * rawDx + rawDy * rawDy)

        // Dynamic adaptive low-pass alpha: smooth micro-movements, instant zero-lag fast movements
        val adaptiveAlpha = when {
            speed < 0.05f -> 0.25f
            speed < 0.5f  -> 0.25f + (speed - 0.05f) * 1.4f // Ramps 0.25 .. 0.88
            else          -> 0.88f
        }

        filteredDx = adaptiveAlpha * rawDx + (1f - adaptiveAlpha) * filteredDx
        filteredDy = adaptiveAlpha * rawDy + (1f - adaptiveAlpha) * filteredDy

        // Deadzone thresholding
        val dxAfterDeadzone = if (abs(filteredDx) < deadzoneThreshold) 0f else filteredDx
        val dyAfterDeadzone = if (abs(filteredDy) < deadzoneThreshold) 0f else filteredDy

        if (dxAfterDeadzone == 0f && dyAfterDeadzone == 0f) return

        // Speed-adaptive pointer acceleration (speed is degrees per 10ms sample)
        val accel = when {
            speed < 0.1f -> 0.7f                              // precision zone
            speed < 1.0f -> 0.7f + (speed - 0.1f) * 1.2f      // 0.7x .. 1.78x
            else         -> 1.78f + (speed - 1.0f) * 0.5f
        }.coerceAtMost(2.6f)

        accumX += dxAfterDeadzone * sensitivity * accel
        accumY += dyAfterDeadzone * sensitivity * accel

        val sendX = accumX.toInt()
        val sendY = accumY.toInt()

        if (sendX != 0 || sendY != 0) {
            accumX -= sendX
            accumY -= sendY
            onDisplacementCalculated(sendX, sendY)
        }
    }

    private fun resetState() {
        hasGravity = false
        filteredDx = 0f
        filteredDy = 0f
        accumX = 0f
        accumY = 0f
        lastTimestampNanos = 0L
        suppressUntilMs = 0L
    }
}
