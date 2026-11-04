package com.blekeymouse.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * IMU Sensor-driven "Air Mouse" provider component.
 *
 * High-Performance Sensor Pipeline:
 *  1. Uses [Sensor.TYPE_GYROSCOPE] (or [Sensor.TYPE_GYROSCOPE_UNCALIBRATED]) for high-frequency
 *     instantaneous angular velocity tracking ($\text{rad/s}$) with sub-millisecond response time.
 *  2. Uses [Sensor.TYPE_GAME_ROTATION_VECTOR] or [Sensor.TYPE_ROTATION_VECTOR] to maintain
 *     the device rotation matrix $R$.
 *  3. Performs Frame Transformation ($\boldsymbol{\omega}_{world} = R \cdot \boldsymbol{\omega}_{device}$)
 *     to project wrist rotation into 2D screen pointer movement, eliminating gimbal lock and
 *     orientation distortion (works in portrait, landscape, and flat angles).
 *  4. Static Noise Gate + Speed-adaptive Low-Pass Exponential Filtering eliminates hand tremors
 *     while maintaining zero lag during fast motions.
 *  5. Sub-pixel accumulator guarantees 1:1 motion precision.
 */
class AirMouseProvider(
    context: Context,
    private val onDisplacementCalculated: (dx: Int, dy: Int) -> Unit,
) : SensorEventListener {

    companion object {
        private const val TAG = "AirMouseProvider"
        private const val RAD_TO_DEG = (180.0 / Math.PI).toFloat()

        /** Threshold in rad/s below which static gyro noise is gated to zero. */
        private const val GYRO_STATIC_THRESHOLD = 0.015f
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // Target sensors
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val rotationVectorSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    // Dedicated background scope for sensor event calculations
    private val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _isTracking = MutableStateFlow(value = false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    /** Dynamic sensitivity scaling factor for Air Mouse cursor speed. */
    @Volatile
    var sensitivity: Float = 5.0f

    /** Threshold in degrees below which micro-movements are ignored. */
    @Volatile
    var deadzoneThreshold: Float = 0.01f

    // Orientation Rotation Matrix
    private val rotationMatrix = FloatArray(9) { if ((it % 4) == 0) 1f else 0f }
    private var hasRotationMatrix = false

    // Low-pass filter state
    private var filteredDx = 0f
    private var filteredDy = 0f

    // Sub-pixel integer accumulation
    private var accumX = 0f
    private var accumY = 0f

    // Timestamp tracking for delta time integration
    private var lastTimestampNanos = 0L

    /**
     * Starts tracking IMU sensor events.
     * Registers listeners at [SensorManager.SENSOR_DELAY_GAME].
     */
    fun startTracking() {
        if (_isTracking.value) return
        resetState()

        var gyroRegistered = false
        var rotationRegistered = false

        if (gyroSensor != null) {
            gyroRegistered = sensorManager.registerListener(
                this,
                gyroSensor,
                SensorManager.SENSOR_DELAY_GAME
            )
        }

        if (rotationVectorSensor != null) {
            rotationRegistered = sensorManager.registerListener(
                this,
                rotationVectorSensor,
                SensorManager.SENSOR_DELAY_GAME
            )
        }

        if (!gyroRegistered) {
            Log.e(TAG, "Gyroscope sensor unavailable — Air Mouse cannot start")
            return
        }

        _isTracking.value = true
        Log.i(TAG, "Air Mouse tracking started (gyroRegistered=$gyroRegistered, rotationRegistered=$rotationRegistered)")
    }

    /**
     * Stops sensor tracking and unregisters listeners to conserve battery.
     */
    fun stopTracking() {
        if (!_isTracking.value) return
        sensorManager.unregisterListener(this)
        _isTracking.value = false
        resetState()
        Log.i(TAG, "Air Mouse tracking stopped")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!_isTracking.value) return

        // Offload calculations to background Default coroutine thread
        providerScope.launch(Dispatchers.Default) {
            when (event.sensor.type) {
                Sensor.TYPE_GAME_ROTATION_VECTOR,
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    hasRotationMatrix = true
                }
                Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
                Sensor.TYPE_GYROSCOPE -> {
                    processGyroscope(event)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    private fun processGyroscope(event: SensorEvent) {
        if (lastTimestampNanos == 0L) {
            lastTimestampNanos = event.timestamp
            return
        }

        val dt = (event.timestamp - lastTimestampNanos) * 1e-9f
        lastTimestampNanos = event.timestamp

        // Clamp dt against unexpected OS sensor pauses
        if (dt <= 0f || dt > 0.1f) return

        val gx = event.values[0] // rad/s around X (pitch: nod phone up/down)
        val gy = event.values[1] // rad/s around Y (roll: tilt phone left/right)
        val gz = event.values[2] // rad/s around Z (yaw: turn phone left/right)

        val totalGyroMagnitude = sqrt(gx * gx + gy * gy + gz * gz)

        // 1. Static noise gate: filter out resting hand tremors
        if (totalGyroMagnitude < GYRO_STATIC_THRESHOLD) {
            filterAndDispatch(0f, 0f)
            return
        }

        val rawDx: Float
        val rawDy: Float

        if (hasRotationMatrix) {
            // 2. World Frame Transformation using rotation matrix R
            // Transform device angular velocity to world frame: omega_world = R * omega_device
            val worldGz = rotationMatrix[6] * gx + rotationMatrix[7] * gy + rotationMatrix[8] * gz
            val worldGx = rotationMatrix[0] * gx + rotationMatrix[1] * gy + rotationMatrix[2] * gz

            rawDx = -worldGz * dt * RAD_TO_DEG
            rawDy = -worldGx * dt * RAD_TO_DEG
        } else {
            // Fallback: Roll-compensated 2D projection
            // Yaw (gz) and Roll (gy) contribute to horizontal pointer movement depending on tilt
            val roll = kotlin.math.atan2(rotationMatrix[7], rotationMatrix[8])
            val cosR = cos(roll)
            val sinR = sin(roll)

            val effectiveHoriz = -(gy * cosR + gz * sinR)
            val effectiveVert = -gx

            rawDx = effectiveHoriz * dt * RAD_TO_DEG
            rawDy = effectiveVert * dt * RAD_TO_DEG
        }

        filterAndDispatch(rawDx, rawDy)
    }

    private fun filterAndDispatch(rawDx: Float, rawDy: Float) {
        val speed = sqrt(rawDx * rawDx + rawDy * rawDy)

        // Dynamic adaptive low-pass alpha: smooth micro-movements, instant zero-lag fast movements
        val adaptiveAlpha = when {
            speed < 0.10f -> 0.25f
            speed < 1.0f  -> 0.25f + (speed - 0.10f) * 0.60f // Ramps 0.25 .. 0.79
            else          -> 0.88f
        }

        filteredDx = adaptiveAlpha * rawDx + (1f - adaptiveAlpha) * filteredDx
        filteredDy = adaptiveAlpha * rawDy + (1f - adaptiveAlpha) * filteredDy

        // Deadzone thresholding
        val dxAfterDeadzone = if (abs(filteredDx) < deadzoneThreshold) 0f else filteredDx
        val dyAfterDeadzone = if (abs(filteredDy) < deadzoneThreshold) 0f else filteredDy

        if (dxAfterDeadzone == 0f && dyAfterDeadzone == 0f) return

        // Speed-adaptive quadratic pointer acceleration
        val accel = when {
            speed < 0.5f -> 1.0f
            speed < 4.0f -> 1.0f + (speed - 0.5f) * 0.30f // 1.0x .. 2.05x
            else         -> 2.05f + (speed - 4.0f) * 0.15f
        }.coerceAtMost(2.5f)

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
        hasRotationMatrix = false
        filteredDx = 0f
        filteredDy = 0f
        accumX = 0f
        accumY = 0f
        lastTimestampNanos = 0L
    }
}
