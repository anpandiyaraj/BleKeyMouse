package com.blekeymouse.data

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Low-latency HID report sender.
 *
 * All [BluetoothHidDevice.sendReport] calls are dispatched onto [Dispatchers.IO]
 * (a thread pool optimized for blocking I/O and native calls). A [Mutex] serializes
 * concurrent sends because the underlying BluetoothHidDevice native layer is NOT
 * thread-safe — concurrent calls corrupt the report stream.
 */
class HidReportSender {

    companion object {
        private const val TAG = "HidReportSender"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendMutex = Mutex()

    @Volatile
    private var hidDevice: BluetoothHidDevice? = null

    @Volatile
    private var currentMouseButtons: Int = 0

    @Volatile
    private var pendingDx = 0

    @Volatile
    private var pendingDy = 0

    @Volatile
    private var pendingWheel = 0

    fun attach(device: BluetoothHidDevice) {
        hidDevice = device
        synchronized(this) {
            pendingDx = 0
            pendingDy = 0
            pendingWheel = 0
        }
    }

    fun detach() {
        hidDevice = null
        currentMouseButtons = 0
        synchronized(this) {
            pendingDx = 0
            pendingDy = 0
            pendingWheel = 0
        }
    }

    /**
     * Sends a Mouse HID report (Report ID 0x01) with low-latency delta coalescing.
     */
    fun sendMouseReport(
        host: BluetoothDevice,
        dx: Int,
        dy: Int,
        buttons: Int? = null,
        wheel: Int = 0
    ) {
        val device = hidDevice ?: return
        if (buttons != null) {
            currentMouseButtons = buttons
        }

        synchronized(this) {
            pendingDx += dx
            pendingDy += dy
            pendingWheel += wheel
        }

        scope.launch {
            if (sendMutex.tryLock()) {
                try {
                    while (true) {
                        val sendX: Int
                        val sendY: Int
                        val sendWheel: Int
                        synchronized(this@HidReportSender) {
                            sendX = pendingDx
                            sendY = pendingDy
                            sendWheel = pendingWheel
                            pendingDx = 0
                            pendingDy = 0
                            pendingWheel = 0
                        }

                        if (sendX == 0 && sendY == 0 && sendWheel == 0 && buttons == null) break

                        sendMouseReportInternal(device, host, sendX, sendY, currentMouseButtons, sendWheel)

                        val remaining: Boolean
                        synchronized(this@HidReportSender) {
                            remaining = (pendingDx != 0 || pendingDy != 0 || pendingWheel != 0)
                        }
                        if (!remaining) break
                    }
                } finally {
                    sendMutex.unlock()
                }
            }
        }
    }

    private fun sendMouseReportInternal(
        device: BluetoothHidDevice,
        host: BluetoothDevice,
        dx: Int,
        dy: Int,
        buttons: Int,
        wheel: Int
    ) {
        try {
            val report = ByteArray(HidDescriptors.MOUSE_PAYLOAD_SIZE).apply {
                this[0] = (buttons and 0x07).toByte()
                this[1] = dx.coerceIn(-127, 127).toByte()
                this[2] = dy.coerceIn(-127, 127).toByte()
                this[3] = wheel.coerceIn(-127, 127).toByte()
            }
            val success = device.sendReport(host, HidDescriptors.MOUSE_REPORT_ID.toInt(), report)
            if (!success) {
                Log.w(TAG, "sendMouseReport: sendReport returned false")
            } else {
                Log.v(TAG, "sendMouseReport sent: dx=$dx, dy=$dy, buttons=$buttons, wheel=$wheel")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "sendMouseReport: missing BLUETOOTH_CONNECT permission", e)
        } catch (e: Exception) {
            Log.e(TAG, "sendMouseReport: unexpected error", e)
        }
    }

    /**
     * Sets a mouse button state (down/up) and sends the updated report.
     * Keeps button pressed during subsequent mouse movements.
     */
    fun setMouseButtonState(host: BluetoothDevice, buttonMask: Int, isDown: Boolean) {
        val device = hidDevice ?: return
        scope.launch {
            sendMutex.withLock {
                currentMouseButtons = if (isDown) {
                    currentMouseButtons or buttonMask
                } else {
                    currentMouseButtons and buttonMask.inv()
                }
                sendMouseReportInternal(device, host, dx = 0, dy = 0, buttons = currentMouseButtons, wheel = 0)
            }
        }
    }

    /**
     * Sends a click (press button down, hold 50ms, release button).
     */
    fun sendMouseClick(host: BluetoothDevice, buttonMask: Int) {
        val device = hidDevice ?: return
        scope.launch {
            sendMutex.withLock {
                val pressButtons = currentMouseButtons or buttonMask
                sendMouseReportInternal(device, host, dx = 0, dy = 0, buttons = pressButtons, wheel = 0)
            }
            kotlinx.coroutines.delay(50)
            sendMutex.withLock {
                sendMouseReportInternal(device, host, dx = 0, dy = 0, buttons = currentMouseButtons, wheel = 0)
            }
        }
    }

    /**
     * Sends a Keyboard HID report (Report ID 0x02).
     *
     * Report payload (8 bytes excluding Report ID):
     *   [0] = modifier byte
     *   [1] = reserved (always 0x00)
     *   [2..7] = up to 6 simultaneous HID keycodes
     */
    fun sendKeyboardReport(
        host: BluetoothDevice,
        modifier: Int = 0,
        keycodes: List<Int> = emptyList()
    ) {
        val device = hidDevice ?: run {
            Log.w(TAG, "sendKeyboardReport: hidDevice not attached, dropping report")
            return
        }
        scope.launch {
            sendMutex.withLock {
                try {
                    val report = ByteArray(HidDescriptors.KEYBOARD_PAYLOAD_SIZE).apply {
                        this[0] = (modifier and 0xFF).toByte()
                        this[1] = 0x00 // reserved
                        keycodes.take(6).forEachIndexed { i, code ->
                            this[2 + i] = (code and 0xFF).toByte()
                        }
                    }
                    val success = device.sendReport(host, HidDescriptors.KEYBOARD_REPORT_ID.toInt(), report)
                    if (!success) {
                        Log.w(TAG, "sendKeyboardReport: sendReport returned false")
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "sendKeyboardReport: missing BLUETOOTH_CONNECT permission", e)
                } catch (e: Exception) {
                    Log.e(TAG, "sendKeyboardReport: unexpected error", e)
                }
            }
        }
    }

    fun sendKeyRelease(host: BluetoothDevice) {
        sendKeyboardReport(host, modifier = 0, keycodes = emptyList())
    }

    fun sendMouseRelease(host: BluetoothDevice) {
        currentMouseButtons = 0
        sendMouseReport(host, dx = 0, dy = 0, buttons = 0, wheel = 0)
    }

    fun release() {
        hidDevice = null
        scope.cancel()
    }
}
