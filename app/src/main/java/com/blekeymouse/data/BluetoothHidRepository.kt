package com.blekeymouse.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.blekeymouse.service.BluetoothHidService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * Repository abstracting all Bluetooth HID operations from the UI/ViewModel layer.
 *
 * Acts as the single access point for:
 *  • Starting and stopping [BluetoothHidService]
 *  • Querying [ConnectionState] as a reactive [Flow]
 *  • Listing paired devices
 *  • Sending mouse and keyboard HID reports
 *
 * Binds to [BluetoothHidService] using [Context.BIND_AUTO_CREATE], keeping the
 * service alive as long as this repository is bound (which spans the app process lifetime
 * when held by [AppModule]).
 *
 * Threading: all public methods are safe to call from any thread or coroutine context.
 * The underlying [HidReportSender] dispatches onto [Dispatchers.IO] internally.
 */
@SuppressLint("MissingPermission")
class BluetoothHidRepository(private val appContext: Context) {

    companion object {
        private const val TAG = "BluetoothHidRepository"
        private const val PREFS_NAME = "ble_key_mouse_prefs"
        private const val KEY_PREFERRED_DEVICE_ADDRESS = "preferred_device_address"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Service Binding
    // ─────────────────────────────────────────────────────────────────────────

    private val _boundService = MutableStateFlow<BluetoothHidService?>(null)
    private val boundService: BluetoothHidService?
        get() = _boundService.value

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    // Pending device to connect to once service finishes binding
    @Volatile
    private var pendingConnectDevice: BluetoothDevice? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as BluetoothHidService.LocalBinder
            val svc = binder.getService()
            _boundService.value = svc
            Log.i(TAG, "Bound to BluetoothHidService")

            // If a connect was requested while binding was in flight, execute it now
            pendingConnectDevice?.let { device ->
                pendingConnectDevice = null
                svc.connectTo(device)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.w(TAG, "BluetoothHidService unexpectedly disconnected")
            _boundService.value = null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Service Control
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Starts the foreground HID service and binds to it.
     * Safe to call multiple times — binding is idempotent.
     */
    fun startService() {
        Log.i(TAG, "startService")
        val intent = Intent(appContext, BluetoothHidService::class.java)
        appContext.startForegroundService(intent)
        appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        _isServiceRunning.value = true
    }

    /**
     * Unbinds and stops the foreground HID service.
     * Clears the preferred device, stopping auto-reconnect.
     */
    fun stopService() {
        Log.i(TAG, "stopService")
        pendingConnectDevice = null
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PREFERRED_DEVICE_ADDRESS)
            .apply()
        try {
            appContext.unbindService(serviceConnection)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "stopService: service was not bound")
        }
        _boundService.value = null
        appContext.stopService(Intent(appContext, BluetoothHidService::class.java))
        _isServiceRunning.value = false
    }

    /**
     * Checks whether a preferred target device address was saved previously.
     */
    fun hasPreferredDevice(): Boolean {
        val savedAddress = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PREFERRED_DEVICE_ADDRESS, null)
        return !savedAddress.isNullOrBlank()
    }

    /**
     * Auto-starts the HID service if a preferred device was saved from a previous session.
     */
    fun autoConnectIfPossible() {
        if (hasPreferredDevice()) {
            Log.i(TAG, "autoConnectIfPossible: preferred device saved — auto-starting HID service")
            startService()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Air Mouse Controls
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a reactive [Flow] indicating whether Air Mouse sensor tracking is currently active.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun getIsAirMouseActiveFlow(): Flow<Boolean> {
        return _boundService.flatMapLatest { service ->
            service?.isAirMouseTrackingFlow() ?: flowOf(false)
        }
    }

    /** Starts Air Mouse tracking via the bound service. */
    fun startAirMouse() {
        boundService?.startAirMouse()
    }

    /** Stops Air Mouse tracking via the bound service. */
    fun stopAirMouse() {
        boundService?.stopAirMouse()
    }

    /** Toggles Air Mouse tracking on/off. */
    fun toggleAirMouse() {
        val service = boundService ?: return
        if (service.isAirMouseTrackingFlow().value) {
            service.stopAirMouse()
        } else {
            service.startAirMouse()
        }
    }

    /** Updates the Air Mouse dynamic sensitivity multiplier. */
    fun setAirMouseSensitivity(sensitivity: Float) {
        boundService?.setAirMouseSensitivity(sensitivity)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Connection State
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a reactive [Flow] of [ConnectionState] that automatically switches
     * to the bound service's live state flow when connected, and falls back to
     * [ConnectionState.Disconnected] when unbound.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun getConnectionStateFlow(): Flow<ConnectionState> {
        return _boundService.flatMapLatest { service ->
            service?.connectionStateFlow() ?: flowOf(ConnectionState.Disconnected)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Device Operations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the list of Bluetooth-paired (bonded) devices.
     * The ViewModel displays these in the paired device list screen.
     */
    fun getPairedDevices(): Set<BluetoothDevice> {
        val btManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return try {
            btManager.adapter?.bondedDevices ?: emptySet()
        } catch (e: SecurityException) {
            Log.e(TAG, "getPairedDevices: BLUETOOTH_CONNECT permission missing", e)
            emptySet()
        }
    }

    /**
     * Instructs the service to connect to [device] as the preferred HID host.
     * Sets this device as the auto-reconnect target.
     */
    fun connectToDevice(device: BluetoothDevice) {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREFERRED_DEVICE_ADDRESS, device.address)
            .apply()

        val service = boundService
        if (service == null) {
            Log.i(TAG, "connectToDevice: service not bound yet — queuing ${device.address} and starting service")
            pendingConnectDevice = device
            startService()
            return
        }
        service.connectTo(device)
    }

    /**
     * Disconnects from the current device and clears the preferred device.
     * Auto-reconnect will not trigger after this call.
     */
    fun disconnect() {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PREFERRED_DEVICE_ADDRESS)
            .apply()
        boundService?.disconnect()
    }

    /** Returns the currently connected [BluetoothDevice], or null. */
    fun getConnectedDevice(): BluetoothDevice? = boundService?.connectedHost()

    // ─────────────────────────────────────────────────────────────────────────
    // HID Report Forwarding
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Forwards a mouse movement report to the connected host.
     *
     * @param dx      Horizontal delta (positive = right)
     * @param dy      Vertical delta (positive = down)
     * @param buttons Optional button bitmask (null = retain active button state)
     * @param wheel   Scroll delta (positive = scroll up)
     */
    fun sendMouseReport(dx: Int, dy: Int, buttons: Int? = null, wheel: Int = 0) {
        val service = boundService ?: return
        val host = service.connectedHost() ?: return
        service.reportSender().sendMouseReport(host, dx, dy, buttons, wheel)
    }

    /**
     * Sets a mouse button state (down/up).
     * @param buttonMask 0x01 for Left, 0x02 for Right
     */
    fun setMouseButtonState(buttonMask: Int, isDown: Boolean) {
        val service = boundService ?: return
        val host = service.connectedHost() ?: return
        service.reportSender().setMouseButtonState(host, buttonMask, isDown)
    }

    /**
     * Sends a mouse click (press down, 50ms hold, release).
     * @param buttonMask 0x01 for Left, 0x02 for Right
     */
    fun sendMouseClick(buttonMask: Int) {
        val service = boundService ?: return
        val host = service.connectedHost() ?: return
        service.reportSender().sendMouseClick(host, buttonMask)
    }

    /**
     * Sends a mouse button release report (all buttons up, zero deltas).
     */
    fun sendMouseRelease() {
        val service = boundService ?: return
        val host = service.connectedHost() ?: return
        service.reportSender().sendMouseRelease(host)
    }

    /**
     * Forwards a keyboard key press report to the connected host.
     *
     * @param modifier Modifier bitmask (bit0=L-Ctrl, bit1=L-Shift, bit2=L-Alt, bit3=L-GUI, ...)
     * @param keycodes List of up to 6 USB HID keycodes to press simultaneously.
     */
    fun sendKeyboardReport(modifier: Int = 0, keycodes: List<Int> = emptyList()) {
        val service = boundService ?: return
        val host = service.connectedHost() ?: return
        service.reportSender().sendKeyboardReport(host, modifier, keycodes)
    }

    /**
     * Sends a keyboard key release (all keys up).
     * Always call this after [sendKeyboardReport] to prevent stuck keys.
     */
    fun sendKeyRelease() {
        val service = boundService ?: return
        val host = service.connectedHost() ?: return
        service.reportSender().sendKeyRelease(host)
    }
}
