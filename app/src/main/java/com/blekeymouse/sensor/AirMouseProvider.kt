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

/**
 * IMU Sensor-driven "Air Mouse" provider component.
 *
 * Specifications & Signal Processing:
 *  1. Uses [Sensor.TYPE_ROTATION_VECTOR] (Sensor Fusion) as primary data source.
 *     Falls back to combined [Sensor.TYPE_GYROSCOPE] and [Sensor.TYPE_ACCELEROMETER]
 *     if Rotation Vector hardware sensor is unavailable.
 *  2. Implements exponential low-pass filtering and deadzone thresholding to
 *     eliminate micro-jitters and natural hand tremors.
 *  3. Calculates delta changes in Pitch (up/down) and Yaw (left/right) and converts
 *     them into relative integer X/Y displacements.
 *  4. Offloads math and sensor calculations to [Dispatchers.Default] background coroutine thread.
 *  5. Provides lifecycle controls ([startTracking], [stopTracking]) for battery optimization.
 */
class AirMouseProvider(
    context: Context,
    private val onDisplacementCalculated: (dx: Int, dy: Int) -> Unit
) : SensorEventListener {

    companion object {
        private const val TAG = "AirMouseProvider"
        private const val RAD_TO_DEG = 180f / Math.PI.toFloat()
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // Target sensors
    private val rotationVectorSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // Dedicated background scope for sensor event calculations
    private val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    /** Dynamic sensitivity scaling factor for Air Mouse cursor speed. */
    @Volatile
    var sensitivity: Float = 4.5f

    /** Alpha coefficient for low-pass exponential smoothing (0.0..1.0). */
    @Volatile
    var lowPassAlpha: Float = 0.40f

    /** Threshold (in degrees) below which micro-movements are ignored. */
    @Volatile
    var deadzoneThreshold: Float = 0.01f

    private var usingRotationVector = false

    // Orientation state
    private val rotationMatrix = FloatArray(9)
    private val orientationValues = FloatArray(3)

    private var lastYaw = 0f
    private var lastPitch = 0f
    private var lastRoll = 0f
    private var hasFirstOrientation = false

    // Low-pass filter state
    private var filteredDx = 0f
    private var filteredDy = 0f

    // Sub-pixel integer accumulation
    private var accumX = 0f
    private var accumY = 0f

    // Fallback gyro state
    private var lastTimestampNanos = 0L

    /**
     * Starts tracking IMU sensor events.
     * Registers listeners at [SensorManager.SENSOR_DELAY_GAME].
     */
    fun startTracking() {
        if (_isTracking.value) return
        resetState()

        if (rotationVectorSensor != null) {
            usingRotationVector = sensorManager.registerListener(
                this,
                rotationVectorSensor,
                SensorManager.SENSOR_DELAY_GAME
            )
        }

        if (!usingRotationVector) {
            Log.w(TAG, "TYPE_ROTATION_VECTOR unavailable — falling back to Gyroscope/Accelerometer")
            val gyroReg = gyroSensor != null && sensorManager.registerListener(
                this,
                gyroSensor,
                SensorManager.SENSOR_DELAY_GAME
            )
            val accelReg = accelSensor != null && sensorManager.registerListener(
                this,
                accelSensor,
                SensorManager.SENSOR_DELAY_GAME
            )
            if (!gyroReg && !accelReg) {
                Log.e(TAG, "Neither Rotation Vector nor Gyroscope sensors are available on this device")
                return
            }
        }

        _isTracking.value = true
        Log.i(TAG, "Air Mouse tracking started (usingRotationVector=$usingRotationVector)")
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
                Sensor.TYPE_ROTATION_VECTOR -> processRotationVector(event)
                Sensor.TYPE_GYROSCOPE -> processGyroscope(event)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    private fun processRotationVector(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientationValues)

        val yaw = orientationValues[0]   // azimuth around Z (-PI..PI)
        val pitch = orientationValues[1] // pitch around X (-PI/2..PI/2)
        val roll = orientationValues[2]  // roll around Y (-PI..PI)

        if (!hasFirstOrientation) {
            lastYaw = yaw
            lastPitch = pitch
            lastRoll = roll
            hasFirstOrientation = true
            return
        }

        val deltaYaw = normalizeAngleDelta(yaw - lastYaw)
        val deltaPitch = normalizeAngleDelta(pitch - lastPitch)
        val deltaRoll = normalizeAngleDelta(roll - lastRoll)

        lastYaw = yaw
        lastPitch = pitch
        lastRoll = roll

        // Combine roll and yaw based on pitch angle for accurate 2D screen motion
        val cosPitch = kotlin.math.cos(pitch)
        val sinPitch = kotlin.math.sin(pitch)

        val effectiveDeltaHorizontal = -(deltaRoll * cosPitch + deltaYaw * sinPitch)
        val effectiveDeltaVertical = -deltaPitch

        val rawDx = effectiveDeltaHorizontal * RAD_TO_DEG
        val rawDy = effectiveDeltaVertical * RAD_TO_DEG

        filterAndDispatch(rawDx, rawDy)
    }

    private fun processGyroscope(event: SensorEvent) {
        if (lastTimestampNanos == 0L) {
            lastTimestampNanos = event.timestamp
            return
        }

        val dt = (event.timestamp - lastTimestampNanos) * 1e-9f
        lastTimestampNanos = event.timestamp

        val gyroX = event.values[0] // rad/s around X
        val gyroY = event.values[1] // rad/s around Y
        val gyroZ = event.values[2] // rad/s around Z

        // Combine Y and Z gyro rates for horizontal motion when pointing at screen
        val rawDx = -(gyroY + gyroZ * 0.5f) * dt * RAD_TO_DEG
        val rawDy = -gyroX * dt * RAD_TO_DEG

        filterAndDispatch(rawDx, rawDy)
    }

    private fun filterAndDispatch(rawDx: Float, rawDy: Float) {
        val speed = kotlin.math.sqrt(rawDx * rawDx + rawDy * rawDy)

        // Dynamic adaptive low-pass alpha: smooth at low speed, zero-lag responsive at high speed
        val adaptiveAlpha = when {
            speed < 0.15f -> 0.20f
            speed < 1.0f  -> 0.20f + (speed - 0.15f) * 0.65f // Ramps 0.20 .. 0.75
            else          -> 0.85f
        }

        filteredDx = adaptiveAlpha * rawDx + (1f - adaptiveAlpha) * filteredDx
        filteredDy = adaptiveAlpha * rawDy + (1f - adaptiveAlpha) * filteredDy

        // Deadzone thresholding
        val dxAfterDeadzone = if (abs(filteredDx) < deadzoneThreshold) 0f else filteredDx
        val dyAfterDeadzone = if (abs(filteredDy) < deadzoneThreshold) 0f else filteredDy

        if (dxAfterDeadzone == 0f && dyAfterDeadzone == 0f) return

        // Non-linear air mouse motion acceleration
        val accel = when {
            speed < 0.5f  -> 1.0f
            speed < 3.0f  -> 1.0f + (speed - 0.5f) * 0.25f // 1.0× .. 1.625×
            else          -> 1.625f + (speed - 3.0f) * 0.15f
        }.coerceAtMost(2.0f)

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

    private fun normalizeAngleDelta(delta: Float): Float {
        var d = delta
        val pi = Math.PI.toFloat()
        while (d > pi) d -= 2 * pi
        while (d < -pi) d += 2 * pi
        return d
    }

    private fun resetState() {
        hasFirstOrientation = false
        lastYaw = 0f
        lastPitch = 0f
        filteredDx = 0f
        filteredDy = 0f
        accumX = 0f
        accumY = 0f
        lastTimestampNanos = 0L
    }
}
