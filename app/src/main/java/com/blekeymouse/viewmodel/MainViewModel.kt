package com.blekeymouse.viewmodel

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.blekeymouse.data.BluetoothHidRepository
import com.blekeymouse.data.ConnectionState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel bridging the UI layer and [BluetoothHidRepository].
 *
 * All [StateFlow]s exposed here are safe to collect from Compose [collectAsStateWithLifecycle].
 *
 * This ViewModel does NOT hold any Context references directly — it delegates all
 * Bluetooth operations to the repository (which holds applicationContext).
 */
@SuppressLint("MissingPermission")
class MainViewModel(
    private val repository: BluetoothHidRepository
) : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"

        /** Provides the ViewModel with the repository via a custom factory. */
        fun factory(repository: BluetoothHidRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MainViewModel(repository) as T
                }
            }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Connection State — delegated to repository / service
    // ─────────────────────────────────────────────────────────────────────────

    val connectionState: StateFlow<ConnectionState> = repository
        .getConnectionStateFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ConnectionState.Disconnected
        )

    // ─────────────────────────────────────────────────────────────────────────
    // Service running state
    // ─────────────────────────────────────────────────────────────────────────

    val isServiceRunning: StateFlow<Boolean> = repository.isServiceRunning
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false
        )

    // ─────────────────────────────────────────────────────────────────────────
    // Paired Devices — automatically reacts to system bond state changes
    // ─────────────────────────────────────────────────────────────────────────

    val pairedDevices: StateFlow<List<BluetoothDevice>> = repository.pairedDevicesFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    /** Refreshes the paired devices list from the Bluetooth adapter. */
    fun refreshPairedDevices() {
        viewModelScope.launch {
            repository.refreshPairedDevices()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Service Control
    // ─────────────────────────────────────────────────────────────────────────

    /** Toggles the foreground HID service on/off. */
    fun toggleService() {
        if (isServiceRunning.value) {
            Log.i(TAG, "toggleService: stopping service")
            repository.stopService()
        } else {
            Log.i(TAG, "toggleService: starting service")
            repository.startService()
        }
    }

    /** Auto-connects on cold launch if a preferred target device is saved. */
    fun autoConnectIfPossible() {
        repository.autoConnectIfPossible()
    }

    val autoConnectDeviceAddress: StateFlow<String?> = repository.autoConnectDeviceAddress
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = repository.getAutoConnectDeviceAddress()
        )

    fun setAutoConnectDevice(deviceAddress: String, enabled: Boolean) {
        repository.setAutoConnectDevice(deviceAddress, enabled)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Air Mouse State & Controls
    // ─────────────────────────────────────────────────────────────────────────

    val isAirMouseActive: StateFlow<Boolean> = repository
        .getIsAirMouseActiveFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false
        )

    fun toggleAirMouse() {
        repository.toggleAirMouse()
    }

    fun setAirMouseSensitivity(sensitivity: Float) {
        repository.setAirMouseSensitivity(sensitivity)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Device Connection
    // ─────────────────────────────────────────────────────────────────────────

    /** Connects to the specified [device] as the HID host. */
    fun connectTo(device: BluetoothDevice) {
        Log.i(TAG, "connectTo: ${device.address}")
        if (!isServiceRunning.value) {
            repository.startService()
        }
        repository.connectToDevice(device)
    }

    /** Disconnects from the current device. */
    fun disconnect() {
        Log.i(TAG, "disconnect")
        repository.disconnect()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HID Report Sending
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sends a mouse movement report.
     * Called by the touchpad gesture handler on every drag event.
     */
    fun sendMouseMove(dx: Int, dy: Int) {
        repository.sendMouseReport(dx = dx, dy = dy)
    }

    /** Sets a mouse button state (down/up). e.g. 0x01 for Left, 0x02 for Right. */
    fun setMouseButtonState(buttonMask: Int, isDown: Boolean) {
        repository.setMouseButtonState(buttonMask, isDown)
    }

    /** Sends a left-click with a 50ms hold duration. */
    fun sendLeftClick() {
        repository.sendMouseClick(0x01)
    }

    /** Sends a right-click with a 50ms hold duration. */
    fun sendRightClick() {
        repository.sendMouseClick(0x02)
    }

    /** Sends a scroll wheel event. Positive [delta] = scroll up. */
    fun sendScroll(delta: Int) {
        repository.sendMouseReport(dx = 0, dy = 0, wheel = delta)
    }

    /** Sends a horizontal scroll wheel event via Shift+Wheel modifier. Positive [delta] = scroll right. */
    fun sendHorizontalScroll(delta: Int) {
        repository.sendKeyboardReport(modifier = 0x02, keycodes = emptyList())
        repository.sendMouseReport(dx = 0, dy = 0, wheel = delta)
        repository.sendKeyRelease()
    }

    /** Sends Escape key (USB HID keycode 0x29). Acts as ESC on PC and Back button on Android. */
    fun sendEscapeKey() {
        sendKeyCombo(modifier = 0, keycodes = listOf(0x29))
    }

    /**
     * Sends a keyboard key combo.
     * @param modifier Modifier bitmask
     * @param keycodes USB HID keycodes (up to 6)
     */
    fun sendKeyCombo(modifier: Int = 0, keycodes: List<Int> = emptyList()) {
        repository.sendKeyboardReport(modifier, keycodes)
        repository.sendKeyRelease()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cleanup
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "onCleared")
        // Repository is application-scoped, do NOT stop the service here.
        // The user controls service lifecycle explicitly via toggleService().
    }
}
