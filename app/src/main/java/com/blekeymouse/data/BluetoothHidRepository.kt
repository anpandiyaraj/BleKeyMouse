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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

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
        private const val KEY_AUTO_CONNECT_ENABLED = "auto_connect_enabled"
        private const val KEY_AUTO_CONNECT_DEVICE_ADDRESS = "auto_connect_device_address"
        private const val KEY_AIR_MOUSE_SENSITIVITY = "air_mouse_sensitivity"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Service Binding
    // ─────────────────────────────────────────────────────────────────────────

    private val _boundService = MutableStateFlow<BluetoothHidService?>(null)
    private val boundService: BluetoothHidService?
        get() = _boundService.value

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _autoConnectDeviceAddress = MutableStateFlow<String?>(getAutoConnectDeviceAddressInternal())
    val autoConnectDeviceAddress: StateFlow<String?> = _autoConnectDeviceAddress.asStateFlow()

    // Pending device to connect to once service finishes binding
    @Volatile
    private var pendingConnectDevice: BluetoothDevice? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as BluetoothHidService.LocalBinder
            val svc = binder.getService()
            svc.onServiceDestroyed = {
                _boundService.value = null
                _isServiceRunning.value = false
            }
            _boundService.value = svc
            _isServiceRunning.value = true
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
    // Auto-Connect Preferences
    // ─────────────────────────────────────────────────────────────────────────

    private fun getAutoConnectDeviceAddressInternal(): String? {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_AUTO_CONNECT_ENABLED, false)
        if (!enabled) return null
        return prefs.getString(KEY_AUTO_CONNECT_DEVICE_ADDRESS, null)
    }

    fun isAutoConnectEnabled(): Boolean {
        return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_CONNECT_ENABLED, false)
    }

    fun getAutoConnectDeviceAddress(): String? = getAutoConnectDeviceAddressInternal()

    /**
     * Selects ONE paired device (or none when `enabled = false`) for Auto-Connect.
     *
     *  • When `enabled = true`: marks [deviceAddress] as the single Auto-Connect target and
     *    immediately notifies the running foreground service to connect if currently idle.
     *  • When `enabled = false`: clears Auto-Connect so cold start / background service will
     *    NOT auto-connect to any device.
     */
    fun setAutoConnectDevice(deviceAddress: String, enabled: Boolean) {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (enabled) {
            prefs.edit()
                .putBoolean(KEY_AUTO_CONNECT_ENABLED, true)
                .putString(KEY_AUTO_CONNECT_DEVICE_ADDRESS, deviceAddress)
                .apply()
            _autoConnectDeviceAddress.value = deviceAddress
            Log.i(TAG, "setAutoConnectDevice: enabled for $deviceAddress")

            val btManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val device = try { btManager.adapter?.getRemoteDevice(deviceAddress) } catch (_: Exception) { null }
            boundService?.updateAutoConnectTarget(device)
        } else {
            val current = prefs.getString(KEY_AUTO_CONNECT_DEVICE_ADDRESS, null)
            if (current.equals(deviceAddress, ignoreCase = true)) {
                prefs.edit()
                    .putBoolean(KEY_AUTO_CONNECT_ENABLED, false)
                    .remove(KEY_AUTO_CONNECT_DEVICE_ADDRESS)
                    .remove(KEY_PREFERRED_DEVICE_ADDRESS)
                    .apply()
                _autoConnectDeviceAddress.value = null
                boundService?.updateAutoConnectTarget(null)
                Log.i(TAG, "setAutoConnectDevice: disabled for $deviceAddress")
            }
        }
    }

    /** Clears any selected Auto-Connect device ("None — Manual connect only"). */
    fun clearAutoConnectDevice() {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_AUTO_CONNECT_ENABLED, false)
            .remove(KEY_AUTO_CONNECT_DEVICE_ADDRESS)
            .remove(KEY_PREFERRED_DEVICE_ADDRESS)
            .apply()
        _autoConnectDeviceAddress.value = null
        boundService?.updateAutoConnectTarget(null)
        Log.i(TAG, "clearAutoConnectDevice: auto-connect disabled for all devices")
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
     */
    fun stopService() {
        Log.i(TAG, "stopService")
        pendingConnectDevice = null
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
     * Checks whether auto-connect is set for a device.
     */
    fun hasPreferredDevice(): Boolean {
        return !getAutoConnectDeviceAddressInternal().isNullOrBlank()
    }

    /**
     * Starts the HID service by default on app launch so the HID profile is registered
     * and ready.
     *
     * Auto-Connect behavior on launch:
     *  • If ONE paired device is selected for Auto-Connect, initiates connection to it.
     *  • If NO paired device is selected for Auto-Connect, does NOT connect to any device
     *    (waits in standby for the user to tap Connect).
     */
    fun autoConnectIfPossible() {
        Log.i(TAG, "autoConnectIfPossible: starting HID service on app launch")
        startService()

        val autoDevice = getAutoConnectDeviceAddressInternal()
        if (isAutoConnectEnabled() && !autoDevice.isNullOrBlank()) {
            val btManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val device = try {
                btManager.adapter?.getRemoteDevice(autoDevice)
            } catch (e: Exception) {
                null
            }
            if (device != null && device.bondState == BluetoothDevice.BOND_BONDED) {
                Log.i(TAG, "autoConnectIfPossible: Auto-Connect enabled for $autoDevice — initiating connection")
                connectToDevice(device)
            } else {
                Log.i(TAG, "autoConnectIfPossible: saved Auto-Connect device $autoDevice is no longer bonded — clearing")
                clearAutoConnectDevice()
            }
        } else {
            Log.i(TAG, "autoConnectIfPossible: no Auto-Connect device selected — staying disconnected")
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
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_AIR_MOUSE_SENSITIVITY, sensitivity)
            .apply()
        boundService?.setAirMouseSensitivity(sensitivity)
    }

    /** Current Air Mouse sensitivity (reads from preferences if service isn't bound yet). */
    fun getAirMouseSensitivity(): Float {
        boundService?.let { return it.airMouseSensitivity() }
        return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_AIR_MOUSE_SENSITIVITY, com.blekeymouse.sensor.AirMouseProvider.DEFAULT_SENSITIVITY)
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

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _pairedDevicesFlow = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val pairedDevicesFlow: StateFlow<List<BluetoothDevice>> = _pairedDevicesFlow.asStateFlow()

    private val bondStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Bluetooth event received (${intent.action}) — refreshing paired devices")
            refreshPairedDevices()
            repoScope.launch {
                delay(500)
                refreshPairedDevices()
            }
        }
    }

    init {
        try {
            val filter = android.content.IntentFilter().apply {
                addAction(android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            appContext.registerReceiver(bondStateReceiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register bondStateReceiver", e)
        }
        refreshPairedDevices()
    }

    /**
     * Queries the Bluetooth adapter for currently bonded devices and updates the reactive flow.
     */
    fun refreshPairedDevices(): List<BluetoothDevice> {
        val btManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val devices = try {
            btManager.adapter?.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            Log.e(TAG, "refreshPairedDevices: BLUETOOTH_CONNECT permission missing", e)
            emptyList()
        }
        _pairedDevicesFlow.value = devices
        return devices
    }

    /**
     * Returns the set of Bluetooth-paired (bonded) devices.
     */
    fun getPairedDevices(): Set<BluetoothDevice> {
        return refreshPairedDevices().toSet()
    }

    /**
     * Instructs the service to connect to [device] for the current session.
     * Note: Manual connection does NOT overwrite the user's Auto-Connect preference.
     */
    fun connectToDevice(device: BluetoothDevice) {
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
     * Disconnects from the current device.
     */
    fun disconnect() {
        boundService?.disconnect()
    }

    /**
     * Unpairs (removes bond) from [device].
     *
     *  1. If currently connected or auto-connecting to [device], disconnects first and clears
     *     Auto-Connect if this device was the Auto-Connect target.
     *  2. Invokes `BluetoothDevice.removeBond()` via reflection (supported across Android 9–15
     *     for apps holding BLUETOOTH_CONNECT / BLUETOOTH_ADMIN).
     *  3. Returns `true` if the system accepted the unbond request, `false` if the OEM
     *     restricts programmatic unbonding (in which case the caller can open system Bluetooth
     *     settings as a fallback).
     */
    fun unpairDevice(device: BluetoothDevice): Boolean {
        val address = device.address
        Log.i(TAG, "unpairDevice: requesting unbond for $address")

        if (_autoConnectDeviceAddress.value.equals(address, ignoreCase = true)) {
            clearAutoConnectDevice()
        }
        if (boundService?.connectedHost()?.address.equals(address, ignoreCase = true)) {
            boundService?.disconnect()
        }

        val removed = try {
            val method = BluetoothDevice::class.java.getMethod("removeBond")
            (method.invoke(device) as? Boolean) ?: false
        } catch (e: Exception) {
            Log.w(TAG, "unpairDevice: removeBond() reflection failed for $address", e)
            false
        }

        repoScope.launch {
            delay(350)
            refreshPairedDevices()
            delay(700)
            refreshPairedDevices()
        }
        return removed
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
     * @param pan     Horizontal scroll delta (positive = scroll right)
     */
    fun sendMouseReport(dx: Int, dy: Int, buttons: Int? = null, wheel: Int = 0, pan: Int = 0) {
        val service = boundService ?: return
        val host = service.connectedHost() ?: return
        service.reportSender().sendMouseReport(host, dx, dy, buttons, wheel, pan)
    }

    /**
     * Sets a mouse button state (down/up).
     * @param buttonMask 0x01 for Left, 0x02 for Right
     */
    fun setMouseButtonState(buttonMask: Int, isDown: Boolean) {
        val service = boundService ?: return
        val host = service.connectedHost() ?: return
        service.notifyButtonActivity()
        service.reportSender().setMouseButtonState(host, buttonMask, isDown)
    }

    /**
     * Sends a mouse click (press down, short hold, release).
     * @param buttonMask 0x01 for Left, 0x02 for Right
     */
    fun sendMouseClick(buttonMask: Int) {
        val service = boundService ?: return
        val host = service.connectedHost() ?: return
        service.notifyButtonActivity()
        service.reportSender().sendMouseClick(host, buttonMask)
    }

    /**
     * Press-drag-release swipe (left button). On Android hosts this is delivered as a touch
     * swipe — use it for YouTube Shorts / Reels, launcher pages, etc.
     */
    fun sendSwipe(dx: Int, dy: Int, durationMs: Long = 160L) {
        val service = boundService ?: return
        val host = service.connectedHost() ?: return
        service.notifyButtonActivity()
        service.reportSender().sendSwipe(host, dx, dy, durationMs)
    }

    /** Press & release a Consumer Control usage (Home, Back, Recents, volume, media). */
    fun sendConsumerKey(usage: Int) {
        val service = boundService ?: return
        val host = service.connectedHost() ?: return
        service.reportSender().sendConsumerTap(host, usage)
    }

    /** Press & release a keyboard combo, guaranteed in order. */
    fun sendKeyTap(modifier: Int = 0, keycodes: List<Int> = emptyList()) {
        val service = boundService ?: return
        val host = service.connectedHost() ?: return
        service.reportSender().sendKeyTap(host, modifier, keycodes)
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
