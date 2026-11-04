package com.blekeymouse.data

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Low-latency HID report sender.
 *
 * Ordering guarantee: every operation is dispatched onto a single-threaded, FIFO
 * dispatcher (`Dispatchers.IO.limitedParallelism(1)`). Previously each report was launched
 * on the multi-threaded IO pool, so a "key down" and the following "key up" could be sent
 * in the wrong order (stuck keys / missing presses). The BluetoothHidDevice native layer is
 * also not thread-safe, so serialising here fixes both problems.
 *
 * Mouse movement is coalesced: deltas accumulate in [pendingDx]/[pendingDy] and the next
 * queued send drains them, so a burst of sensor/touch events never builds a backlog.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HidReportSender {

    companion object {
        private const val TAG = "HidReportSender"
        private const val AXIS_MAX = 32767
        private const val WHEEL_MAX = 127

        /** How long a tap (click/key/consumer) is held — long enough for every host to see it. */
        private const val TAP_HOLD_MS = 40L
    }

    private val sendDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + sendDispatcher)

    @Volatile
    private var hidDevice: BluetoothHidDevice? = null

    @Volatile
    private var currentMouseButtons: Int = 0

    private val lock = Any()
    private var pendingDx = 0
    private var pendingDy = 0
    private var pendingWheel = 0
    private var pendingPan = 0
    private var buttonsDirty = false

    fun attach(device: BluetoothHidDevice) {
        hidDevice = device
        clearPending()
    }

    fun detach() {
        hidDevice = null
        currentMouseButtons = 0
        clearPending()
    }

    private fun clearPending() = synchronized(lock) {
        pendingDx = 0; pendingDy = 0; pendingWheel = 0; pendingPan = 0; buttonsDirty = false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mouse
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Queues a mouse report (Report ID 0x01) with delta coalescing.
     *
     * @param buttons optional new button bitmask (null = keep current state)
     * @param wheel   vertical scroll, positive = scroll up
     * @param pan     horizontal scroll (AC Pan), positive = scroll right
     */
    fun sendMouseReport(
        host: BluetoothDevice,
        dx: Int,
        dy: Int,
        buttons: Int? = null,
        wheel: Int = 0,
        pan: Int = 0
    ) {
        if (hidDevice == null) return
        synchronized(lock) {
            if (buttons != null) {
                currentMouseButtons = buttons and HidDescriptors.BUTTON_MASK
                buttonsDirty = true
            }
            pendingDx += dx
            pendingDy += dy
            pendingWheel += wheel
            pendingPan += pan
        }
        scope.launch { flushMouse(host) }
    }

    /** Drains all pending deltas; splits into several reports if they exceed the axis range. */
    private fun flushMouse(host: BluetoothDevice) {
        val device = hidDevice ?: return
        while (true) {
            val x: Int; val y: Int; val w: Int; val p: Int; val dirty: Boolean
            synchronized(lock) {
                x = pendingDx.coerceIn(-AXIS_MAX, AXIS_MAX)
                y = pendingDy.coerceIn(-AXIS_MAX, AXIS_MAX)
                w = pendingWheel.coerceIn(-WHEEL_MAX, WHEEL_MAX)
                p = pendingPan.coerceIn(-WHEEL_MAX, WHEEL_MAX)
                pendingDx -= x; pendingDy -= y; pendingWheel -= w; pendingPan -= p
                dirty = buttonsDirty
                buttonsDirty = false
            }
            if (x == 0 && y == 0 && w == 0 && p == 0 && !dirty) return
            writeMouse(device, host, currentMouseButtons, x, y, w, p)
        }
    }

    private fun writeMouse(
        device: BluetoothHidDevice,
        host: BluetoothDevice,
        buttons: Int,
        dx: Int,
        dy: Int,
        wheel: Int,
        pan: Int
    ) {
        try {
            val report = ByteArray(HidDescriptors.MOUSE_PAYLOAD_SIZE).apply {
                this[0] = (buttons and HidDescriptors.BUTTON_MASK).toByte()
                this[1] = (dx and 0xFF).toByte()
                this[2] = ((dx shr 8) and 0xFF).toByte()
                this[3] = (dy and 0xFF).toByte()
                this[4] = ((dy shr 8) and 0xFF).toByte()
                this[5] = wheel.toByte()
                this[6] = pan.toByte()
            }
            if (!device.sendReport(host, HidDescriptors.MOUSE_REPORT_ID.toInt(), report)) {
                Log.w(TAG, "sendMouseReport: sendReport returned false")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "sendMouseReport: missing BLUETOOTH_CONNECT permission", e)
        } catch (e: Exception) {
            Log.e(TAG, "sendMouseReport: unexpected error", e)
        }
    }

    /**
     * Sets a mouse button state (down/up) and sends the updated report.
     * Keeps button pressed during subsequent mouse movements (click-and-drag).
     */
    fun setMouseButtonState(host: BluetoothDevice, buttonMask: Int, isDown: Boolean) {
        val newButtons = if (isDown) currentMouseButtons or buttonMask
        else currentMouseButtons and buttonMask.inv()
        sendMouseReport(host, 0, 0, buttons = newButtons)
    }

    /** Sends a click (press, hold [TAP_HOLD_MS], release). */
    fun sendMouseClick(host: BluetoothDevice, buttonMask: Int) {
        val device = hidDevice ?: return
        scope.launch {
            flushMouse(host)
            val base = currentMouseButtons
            writeMouse(device, host, base or buttonMask, 0, 0, 0, 0)
            delay(TAP_HOLD_MS)
            flushMouse(host)
            writeMouse(device, host, currentMouseButtons and buttonMask.inv(), 0, 0, 0, 0)
        }
    }

    /**
     * Performs a press-drag-release swipe with the left button — on an Android host a mouse
     * drag is delivered as a touch swipe, so this reliably flips launcher pages, YouTube
     * Shorts / Reels / TikTok (vertical ViewPagers) and dismisses notifications, where a
     * wheel event may only nudge the content without snapping to the next page.
     *
     * The pointer is returned to its original position afterwards (button released), so
     * repeated swipes don't walk the cursor off-screen.
     *
     * @param totalDx total horizontal travel in mouse counts (host applies pointer speed)
     * @param totalDy total vertical travel; negative = swipe up (next Short)
     */
    fun sendSwipe(host: BluetoothDevice, totalDx: Int, totalDy: Int, durationMs: Long = 160L) {
        val device = hidDevice ?: return
        scope.launch {
            flushMouse(host)
            val steps = 12
            val stepDelay = (durationMs / steps).coerceAtLeast(8L)
            val base = currentMouseButtons and HidDescriptors.BUTTON_LEFT.inv()
            writeMouse(device, host, base or HidDescriptors.BUTTON_LEFT, 0, 0, 0, 0)
            delay(30)
            var sentX = 0
            var sentY = 0
            for (i in 1..steps) {
                // Constant-velocity travel: fast enough (≈ totalDy / durationMs) to register as a fling
                val t = i.toFloat() / steps
                val targetX = (totalDx * t).roundToInt()
                val targetY = (totalDy * t).roundToInt()
                writeMouse(device, host, base or HidDescriptors.BUTTON_LEFT, targetX - sentX, targetY - sentY, 0, 0)
                sentX = targetX
                sentY = targetY
                delay(stepDelay)
            }
            writeMouse(device, host, base, 0, 0, 0, 0)
            delay(30)
            // Move the cursor back to where the swipe started (button up → no gesture)
            writeMouse(device, host, base, -sentX, -sentY, 0, 0)
        }
    }

    fun sendMouseRelease(host: BluetoothDevice) {
        sendMouseReport(host, dx = 0, dy = 0, buttons = 0)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Keyboard
    // ─────────────────────────────────────────────────────────────────────────

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
        scope.launch { writeKeyboard(device, host, modifier, keycodes) }
    }

    /** Press and release a key combo atomically and in order. */
    fun sendKeyTap(host: BluetoothDevice, modifier: Int = 0, keycodes: List<Int> = emptyList()) {
        val device = hidDevice ?: return
        scope.launch {
            writeKeyboard(device, host, modifier, keycodes)
            delay(TAP_HOLD_MS)
            writeKeyboard(device, host, 0, emptyList())
        }
    }

    private fun writeKeyboard(device: BluetoothHidDevice, host: BluetoothDevice, modifier: Int, keycodes: List<Int>) {
        try {
            val report = ByteArray(HidDescriptors.KEYBOARD_PAYLOAD_SIZE).apply {
                this[0] = (modifier and 0xFF).toByte()
                this[1] = 0x00 // reserved
                keycodes.take(6).forEachIndexed { i, code ->
                    this[2 + i] = (code and 0xFF).toByte()
                }
            }
            if (!device.sendReport(host, HidDescriptors.KEYBOARD_REPORT_ID.toInt(), report)) {
                Log.w(TAG, "sendKeyboardReport: sendReport returned false")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "sendKeyboardReport: missing BLUETOOTH_CONNECT permission", e)
        } catch (e: Exception) {
            Log.e(TAG, "sendKeyboardReport: unexpected error", e)
        }
    }

    fun sendKeyRelease(host: BluetoothDevice) {
        sendKeyboardReport(host, modifier = 0, keycodes = emptyList())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Consumer Control (Home / Back / Recents / media / volume)
    // ─────────────────────────────────────────────────────────────────────────

    /** Press and release a Consumer Control usage (see [HidDescriptors.Consumer]). */
    fun sendConsumerTap(host: BluetoothDevice, usage: Int) {
        val device = hidDevice ?: return
        scope.launch {
            writeConsumer(device, host, usage)
            delay(TAP_HOLD_MS)
            writeConsumer(device, host, 0)
        }
    }

    private fun writeConsumer(device: BluetoothHidDevice, host: BluetoothDevice, usage: Int) {
        try {
            val report = byteArrayOf((usage and 0xFF).toByte(), ((usage shr 8) and 0xFF).toByte())
            if (!device.sendReport(host, HidDescriptors.CONSUMER_REPORT_ID.toInt(), report)) {
                Log.w(TAG, "sendConsumer: sendReport returned false")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "sendConsumer: missing BLUETOOTH_CONNECT permission", e)
        } catch (e: Exception) {
            Log.e(TAG, "sendConsumer: unexpected error", e)
        }
    }

    fun release() {
        hidDevice = null
        scope.cancel()
    }
}
