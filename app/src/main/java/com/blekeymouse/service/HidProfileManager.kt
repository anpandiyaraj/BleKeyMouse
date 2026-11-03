package com.blekeymouse.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.util.Log
import com.blekeymouse.data.ConnectionState
import com.blekeymouse.data.HidDescriptors
import com.blekeymouse.data.HidReportSender
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Manages the [BluetoothHidDevice] profile proxy lifecycle, app registration,
 * and all HID callbacks.
 *
 * EDGE CASE — SDP Registry Latency:
 *   [BluetoothHidDevice.registerApp] is asynchronous. The [isAppRegistered] StateFlow
 *   is set to `true` ONLY inside [HidCallback.onAppStatusChanged] when [registered]
 *   is true. All connect() attempts must gate on this flag.
 *
 * EDGE CASE — Bluetooth Toggle Re-Registration:
 *   When [reRegisterApp] is called (triggered by [ConnectionReceiver] on BT STATE_ON),
 *   the existing profile proxy is closed and a fresh [BluetoothProfile.getProfileProxy]
 *   call is made, followed by registerApp() once the proxy reconnects.
 */
@SuppressLint("MissingPermission")
class HidProfileManager(
    private val bluetoothAdapter: BluetoothAdapter,
    private val onDeviceConnected: (BluetoothDevice) -> Unit,
    private val onDeviceDisconnected: (BluetoothDevice) -> Unit,
) {

    companion object {
        private const val TAG = "HidProfileManager"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public state exposed to ConnectionReceiver and BluetoothHidService
    // ─────────────────────────────────────────────────────────────────────────

    /** The live HID device proxy. Null until [BluetoothProfile.ServiceListener.onServiceConnected]. */
    var hidDevice: BluetoothHidDevice? = null
        private set

    /** True only after [onAppStatusChanged] fires with registered=true. */
    private val _isAppRegistered = MutableStateFlow(false)
    val isAppRegistered: StateFlow<Boolean> = _isAppRegistered.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** Currently connected host device (for report sending). */
    var connectedHost: BluetoothDevice? = null
        private set

    // Dedicated single-thread executor for profile proxy callbacks (avoids main thread blocking)
    private val callbackExecutor: Executor = Executors.newSingleThreadExecutor()

    val reportSender = HidReportSender()

    // ─────────────────────────────────────────────────────────────────────────
    // HID Profile Callbacks
    // ─────────────────────────────────────────────────────────────────────────

    private val hidCallback = object : BluetoothHidDevice.Callback() {

        /**
         * EDGE CASE FIX — SDP Registry Latency:
         * Only set [_isAppRegistered] to true here, AFTER the system confirms registration.
         * Any connect() call made before this fires will fail silently.
         */
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.i(TAG, "onAppStatusChanged: registered=$registered, device=${pluggedDevice?.address}")
            _isAppRegistered.value = registered
            if (!registered) {
                _connectionState.value = ConnectionState.Disconnected
            } else if (_connectionState.value is ConnectionState.Registering) {
                _connectionState.value = ConnectionState.Disconnected
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            Log.i(TAG, "onConnectionStateChanged: device=${device.address}, state=$state")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedHost = device
                    reportSender.attach(hidDevice!!)
                    _connectionState.value = ConnectionState.Connected(
                        deviceName  = device.name ?: device.address,
                        deviceAddress = device.address
                    )
                    onDeviceConnected(device)
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    _connectionState.value = ConnectionState.Connecting(
                        deviceName = device.name ?: device.address
                    )
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedHost = null
                    reportSender.detach()
                    _connectionState.value = ConnectionState.Disconnected
                    onDeviceDisconnected(device)
                }
                BluetoothProfile.STATE_DISCONNECTING -> {
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        }

        /**
         * Called when the host requests a specific report via GET_REPORT.
         * We respond with a zero report for the requested type.
         */
        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            Log.d(TAG, "onGetReport: type=$type, id=$id, bufferSize=$bufferSize")
            val hid = hidDevice ?: return
            when (id) {
                HidDescriptors.MOUSE_REPORT_ID -> {
                    val report = ByteArray(HidDescriptors.MOUSE_PAYLOAD_SIZE) { 0 }
                    hid.replyReport(device, type, id, report)
                }
                HidDescriptors.KEYBOARD_REPORT_ID -> {
                    val report = ByteArray(HidDescriptors.KEYBOARD_PAYLOAD_SIZE) { 0 }
                    hid.replyReport(device, type, id, report)
                }
                else -> hid.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
            }
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            Log.d(TAG, "onSetReport: type=$type, id=$id")
            hidDevice?.replyReport(device, type, id, data) // echo back as ACK
        }

        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {
            // Host → Device data (e.g., LED state for keyboard numlock/capslock).
            Log.v(TAG, "onInterruptData: reportId=$reportId, bytes=${data.contentToString()}")
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            Log.i(TAG, "onVirtualCableUnplug: device=${device.address}")
            connectedHost = null
            reportSender.detach()
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BluetoothProfile.ServiceListener
    // ─────────────────────────────────────────────────────────────────────────

    private val profileServiceListener = object : BluetoothProfile.ServiceListener {

        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            Log.i(TAG, "HID profile proxy connected")
            hidDevice = proxy as BluetoothHidDevice
            _connectionState.value = ConnectionState.Registering
            registerApp()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            Log.w(TAG, "HID profile proxy disconnected")
            _isAppRegistered.value = false
            hidDevice = null
            reportSender.detach()
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Initiates the profile proxy acquisition chain.
     * Must be called from [BluetoothHidService.onCreate].
     *
     * [BluetoothAdapter.getProfileProxy] requires a non-null Context on all API levels.
     *
     * @return false if the device does not support the HID_DEVICE profile.
     */
    fun initialize(context: android.content.Context): Boolean {
        Log.i(TAG, "initialize: requesting HID_DEVICE profile proxy")
        return bluetoothAdapter.getProfileProxy(
            context,
            profileServiceListener,
            BluetoothProfile.HID_DEVICE
        ).also { supported ->
            if (!supported) Log.e(TAG, "initialize: HID_DEVICE profile not supported on this device")
        }
    }


    /**
     * Registers our HID app with the Bluetooth stack using the combined descriptor.
     *
     * IMPORTANT: This is asynchronous. [_isAppRegistered] will be set true only inside
     * [hidCallback.onAppStatusChanged]. Do NOT call connect() until that flag is set.
     */
    private fun registerApp() {
        val hid = hidDevice ?: run {
            Log.e(TAG, "registerApp: hidDevice is null — proxy not yet connected")
            return
        }
        Log.i(TAG, "registerApp: registering HID application with Bluetooth stack")
        hid.registerApp(
            /* sdpSettings = */ HidDescriptors.buildSdpSettings(),
            /* inQos       = */ null,
            /* outQos      = */ HidDescriptors.buildQosSettings(),
            /* executor    = */ callbackExecutor,
            /* callback    = */ hidCallback
        )
    }

    /**
     * EDGE CASE FIX — Bluetooth Toggle Re-Registration:
     * Called by [ConnectionReceiver] when BT transitions to STATE_ON.
     * Closes the old (now-stale) proxy and re-acquires a fresh one.
     * [registerApp] will be invoked again inside [onServiceConnected].
     */
    fun reRegisterApp(context: android.content.Context) {
        Log.i(TAG, "reRegisterApp: Bluetooth was toggled, re-acquiring profile proxy")
        _isAppRegistered.value = false
        try {
            hidDevice?.let { bluetoothAdapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        } catch (e: Exception) {
            Log.w(TAG, "reRegisterApp: error closing old proxy", e)
        }
        hidDevice = null
        reportSender.detach()
        initialize(context)
    }

    /**
     * Attempts to connect to [device] via the HID profile.
     *
     * PRE-CONDITION: [isAppRegistered].value must be true before calling this.
     * The caller (service reconnect loop or [ConnectionReceiver]) is responsible
     * for enforcing this gate.
     */
    fun connectToDevice(device: BluetoothDevice): Boolean {
        val hid = hidDevice
        if (hid == null) {
            Log.e(TAG, "connectToDevice: hidDevice proxy is null")
            return false
        }
        if (!_isAppRegistered.value) {
            Log.w(TAG, "connectToDevice: app not registered yet — aborting connect to avoid silent failure")
            return false
        }
        Log.i(TAG, "connectToDevice: calling hidDevice.connect(${device.address})")
        return try {
            hid.connect(device)
        } catch (e: SecurityException) {
            Log.e(TAG, "connectToDevice: BLUETOOTH_CONNECT permission missing", e)
            _connectionState.value = ConnectionState.Error("Permission denied")
            false
        }
    }

    /**
     * Gracefully disconnects from the currently connected device, if any.
     */
    fun disconnectFromDevice(device: BluetoothDevice) {
        val hid = hidDevice ?: return
        try {
            hid.disconnect(device)
        } catch (e: SecurityException) {
            Log.e(TAG, "disconnectFromDevice: SecurityException", e)
        }
    }

    /** Called from service [onDestroy]. Unregisters the app and closes the proxy. */
    fun release(context: android.content.Context) {
        Log.i(TAG, "release: unregistering HID app and closing proxy")
        _isAppRegistered.value = false
        try {
            hidDevice?.unregisterApp()
            hidDevice?.let { bluetoothAdapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        } catch (e: Exception) {
            Log.w(TAG, "release: exception during cleanup", e)
        }
        hidDevice = null
        reportSender.release()
    }
}
