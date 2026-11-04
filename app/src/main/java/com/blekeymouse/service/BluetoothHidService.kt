package com.blekeymouse.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.blekeymouse.MainActivity
import com.blekeymouse.R
import com.blekeymouse.data.ConnectionState
import com.blekeymouse.sensor.AirMouseProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Persistent foreground service that owns the Bluetooth HID profile lifecycle.
 *
 * Responsibilities:
 *  • Promote itself to foreground (with correct type on API 34+) to survive background
 *  • Initialize and manage [HidProfileManager]
 *  • Dynamically register [ConnectionReceiver] for ACL and adapter state events
 *  • Run the continuous auto-reconnect coroutine loop until connected or cancelled
 *  • Expose a [LocalBinder] so [BluetoothHidRepository] can bind and call API methods
 *
 * The service is started via [startForegroundService] from the repository, and stops
 * when the user explicitly toggles it off via [stopSelf].
 */
@SuppressLint("MissingPermission")
class BluetoothHidService : Service() {

    companion object {
        private const val TAG = "BluetoothHidService"

        const val NOTIFICATION_CHANNEL_ID = "ble_hid_service_channel"
        const val NOTIFICATION_ID = 1001

        private const val PREFS_NAME = "ble_key_mouse_prefs"
        private const val KEY_PREFERRED_DEVICE_ADDRESS = "preferred_device_address"
        private const val KEY_AUTO_CONNECT_ENABLED = "auto_connect_enabled"
        private const val KEY_AUTO_CONNECT_DEVICE_ADDRESS = "auto_connect_device_address"

        // Reconnect backoff parameters
        private const val RECONNECT_INITIAL_DELAY_MS = 2_000L
        private const val RECONNECT_MAX_DELAY_MS = 10_000L
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Service Binder
    // ─────────────────────────────────────────────────────────────────────────

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothHidService = this@BluetoothHidService
    }

    private val binder = LocalBinder()

    // ─────────────────────────────────────────────────────────────────────────
    // Internal state
    // ─────────────────────────────────────────────────────────────────────────

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var hidProfileManager: HidProfileManager
    private lateinit var connectionReceiver: ConnectionReceiver
    private lateinit var airMouseProvider: AirMouseProvider

    // Preferred device to reconnect to automatically (set by ViewModel via repository)
    @Volatile
    private var preferredDevice: BluetoothDevice? = null

    // Coroutine scope surviving the full service lifecycle
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Active reconnect job — cancelled when user connects manually or device connects
    private var reconnectJob: Job? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Preference Storage
    // ─────────────────────────────────────────────────────────────────────────

    private fun savePreferredDeviceAddress(address: String) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_PREFERRED_DEVICE_ADDRESS, address)
            .apply()
    }

    private fun clearPreferredDeviceAddress() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .remove(KEY_PREFERRED_DEVICE_ADDRESS)
            .apply()
    }

    private fun restorePreferredDevice(): BluetoothDevice? {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val autoConnectEnabled = prefs.getBoolean(KEY_AUTO_CONNECT_ENABLED, false)
        val savedAddress = if (autoConnectEnabled) {
            prefs.getString(KEY_AUTO_CONNECT_DEVICE_ADDRESS, null)
                ?: prefs.getString(KEY_PREFERRED_DEVICE_ADDRESS, null)
        } else {
            prefs.getString(KEY_PREFERRED_DEVICE_ADDRESS, null)
        } ?: return null

        return try {
            if (BluetoothAdapter.checkBluetoothAddress(savedAddress)) {
                bluetoothAdapter.getRemoteDevice(savedAddress)
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore preferred device address: $savedAddress", e)
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")

        // ── Bluetooth Adapter ──────────────────────────────────────────────
        val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter
            ?: run {
                Log.e(TAG, "Device does not have a Bluetooth adapter — stopping service")
                stopSelf()
                return
            }

        // Restore preferred device from storage if available
        preferredDevice = restorePreferredDevice()
        preferredDevice?.let { device ->
            Log.i(TAG, "Restored preferred device from storage: ${device.address}")
        }

        // ── Air Mouse Provider ────────────────────────────────────────────
        airMouseProvider = AirMouseProvider(
            context = this,
            onDisplacementCalculated = { dx, dy ->
                val host = hidProfileManager.connectedHost
                if (host != null) {
                    hidProfileManager.reportSender.sendMouseReport(host, dx, dy)
                }
            }
        )

        // ── HID Profile Manager ────────────────────────────────────────────
        hidProfileManager = HidProfileManager(
            bluetoothAdapter = bluetoothAdapter,
            onDeviceConnected = { device ->
                Log.i(TAG, "Device connected: ${device.address}")
                preferredDevice = device
                savePreferredDeviceAddress(device.address)
                cancelReconnectLoop() // stop reconnect attempts on success
            },
            onDeviceDisconnected = { device ->
                Log.i(TAG, "Device disconnected: ${device.address}")
                airMouseProvider.stopTracking() // Automatically unregister sensor listener on connection drop
                if (preferredDevice?.address?.equals(device.address, ignoreCase = true) == true) {
                    startReconnectLoop(device) // auto-reconnect
                }
            }
        )

        val profileSupported = hidProfileManager.initialize(this)
        if (!profileSupported) {
            Log.e(TAG, "HID_DEVICE profile not supported — service cannot function")
        }

        // Auto-trigger reconnect whenever app registration completes and preferred device is set
        serviceScope.launch {
            hidProfileManager.isAppRegistered.collect { registered ->
                if (registered) {
                    val pref = preferredDevice
                    val currentState = hidProfileManager.connectionState.value
                    if (pref != null && currentState !is ConnectionState.Connected && currentState !is ConnectionState.Connecting) {
                        Log.i(TAG, "HID app registered with preferred device pending — starting reconnect loop")
                        startReconnectLoop(pref)
                    }
                }
            }
        }

        // ── Broadcast Receiver (dynamic registration) ─────────────────────
        connectionReceiver = ConnectionReceiver(
            hidProfileManager    = hidProfileManager,
            getPreferredDevice   = { preferredDevice },
            onDisconnected       = { device ->
                if (preferredDevice?.address?.equals(device.address, ignoreCase = true) == true) {
                    startReconnectLoop(device)
                }
            }
        )

        val intentFilter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        registerReceiver(connectionReceiver, intentFilter)

        // ── Foreground Notification ────────────────────────────────────────
        createNotificationChannel()
        promoteToForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: startId=$startId")
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        airMouseProvider.stopTracking()
        cancelReconnectLoop()
        try {
            unregisterReceiver(connectionReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "onDestroy: receiver was not registered", e)
        }
        hidProfileManager.release(this)
        serviceScope.cancel()
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API (called via Binder from BluetoothHidRepository)
    // ─────────────────────────────────────────────────────────────────────────

    /** Exposes the connection state flow for repository and ViewModel consumption. */
    fun connectionStateFlow(): StateFlow<ConnectionState> =
        hidProfileManager.connectionState

    /** Exposes the registration state for UI diagnostics. */
    fun isAppRegisteredFlow(): StateFlow<Boolean> =
        hidProfileManager.isAppRegistered

    /**
     * Sets the preferred device and initiates a connection.
     */
    fun connectTo(device: BluetoothDevice) {
        preferredDevice = device
        savePreferredDeviceAddress(device.address)
        cancelReconnectLoop()
        serviceScope.launch {
            val success = hidProfileManager.connectToDevice(device)
            if (!success || hidProfileManager.connectionState.value !is ConnectionState.Connected) {
                startReconnectLoop(device)
            }
        }
    }

    /** Disconnects from the current device and clears the preferred device. */
    fun disconnect() {
        val host = hidProfileManager.connectedHost ?: preferredDevice
        preferredDevice = null
        clearPreferredDeviceAddress()
        cancelReconnectLoop()
        if (host != null) {
            hidProfileManager.disconnectFromDevice(host)
        }
    }

    /** Accessor for the report sender — used by repository for input forwarding. */
    fun reportSender() = hidProfileManager.reportSender

    /** Returns the currently connected host device, if any. */
    fun connectedHost(): BluetoothDevice? = hidProfileManager.connectedHost

    // ─────────────────────────────────────────────────────────────────────────
    // Air Mouse Controls
    // ─────────────────────────────────────────────────────────────────────────

    fun startAirMouse() {
        if (hidProfileManager.connectionState.value is ConnectionState.Connected) {
            airMouseProvider.startTracking()
        } else {
            Log.w(TAG, "startAirMouse requested but device is not connected")
        }
    }

    fun stopAirMouse() {
        airMouseProvider.stopTracking()
    }

    fun isAirMouseTrackingFlow(): StateFlow<Boolean> = airMouseProvider.isTracking

    fun setAirMouseSensitivity(sensitivity: Float) {
        airMouseProvider.sensitivity = sensitivity
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reconnect Loop — Continuous Retry with Backoff
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Starts a reconnect loop targeting [device] that continues attempting connection
     * until connected, cancelled, or the preferred device changes.
     */
    private fun startReconnectLoop(device: BluetoothDevice) {
        if (reconnectJob?.isActive == true) return // already running
        Log.i(TAG, "startReconnectLoop: targeting ${device.address}")

        reconnectJob = serviceScope.launch {
            var attempt = 0
            while (isActive) {
                // Check if device is still preferred
                val currentPreferred = preferredDevice
                if (currentPreferred == null || !currentPreferred.address.equals(device.address, ignoreCase = true)) {
                    Log.i(TAG, "Preferred device cleared or changed — stopping reconnect loop")
                    break
                }

                // Stop loop if already connected
                if (hidProfileManager.connectionState.value is ConnectionState.Connected) {
                    Log.i(TAG, "Device already connected — stopping reconnect loop")
                    break
                }

                val currentState = hidProfileManager.connectionState.value
                val hidState = try { hidProfileManager.hidDevice?.getConnectionState(device) } catch (_: Exception) { null }

                if (currentState is ConnectionState.Connected || hidState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Device already connected — stopping reconnect loop")
                    break
                }

                if (currentState is ConnectionState.Connecting || hidState == android.bluetooth.BluetoothProfile.STATE_CONNECTING) {
                    Log.i(TAG, "Connection currently in progress ($hidState) — waiting for state change")
                    delay(3_000)
                    continue
                }

                val delayMs = if (attempt == 0) 1_000L else minOf(
                    RECONNECT_INITIAL_DELAY_MS + ((attempt - 1) * 3_000L),
                    RECONNECT_MAX_DELAY_MS
                )
                if (delayMs > 0) {
                    Log.i(TAG, "Reconnect loop waiting ${delayMs}ms before attempt #${attempt + 1}")
                    delay(delayMs)
                }

                if (!bluetoothAdapter.isEnabled || !hidProfileManager.isAppRegistered.value) {
                    attempt++
                    continue
                }

                Log.i(TAG, "Executing reconnect attempt #${attempt + 1} calling connectToDevice(${device.address})")
                hidProfileManager.connectToDevice(device)
                attempt++
            }
        }
    }

    private fun cancelReconnectLoop() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Foreground Service + Notification
    // ─────────────────────────────────────────────────────────────────────────

    private fun promoteToForeground() {
        val notification = buildNotification()
        ServiceCompat.startForeground(
            /* service          = */ this,
            /* id               = */ NOTIFICATION_ID,
            /* notification     = */ notification,
            /* foregroundServiceType = */ android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("BleKeyMouse Active")
            .setContentText("Bluetooth HID service is running")
            .setSmallIcon(R.drawable.ic_bluetooth_hid)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "BleKeyMouse Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent Bluetooth HID device service notification"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
