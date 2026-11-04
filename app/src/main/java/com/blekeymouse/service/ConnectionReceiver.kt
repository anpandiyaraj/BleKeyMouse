package com.blekeymouse.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * BroadcastReceiver handling three critical Bluetooth lifecycle events:
 *
 * 1. [BluetoothDevice.ACTION_ACL_CONNECTED]
 *    EDGE CASE FIX — ACL Connected Catch-22:
 *    When a host (PC/TV) initiates a Bluetooth connection, only the generic ACL link
 *    is established. The HID channel must be explicitly opened by calling
 *    hidDevice.connect(). We delay 500ms to allow the baseband handshake to complete,
 *    then connect — but ONLY if [isAppRegistered] is true.
 *
 * 2. [BluetoothDevice.ACTION_ACL_DISCONNECTED]
 *    Notifies the service to trigger the auto-reconnect loop.
 *
 * 3. [BluetoothAdapter.ACTION_STATE_CHANGED]
 *    EDGE CASE FIX — Bluetooth Toggle Re-Registration:
 *    On STATE_ON, the HID app registration is wiped by the system. We must
 *    re-run registerApp() by calling [HidProfileManager.reRegisterApp].
 *
 * This receiver is registered DYNAMICALLY in [BluetoothHidService] (not in the manifest)
 * to ensure it only runs when the service is alive, and so it can access the live
 * [HidProfileManager] and [preferredDeviceAddress] references.
 */
@SuppressLint("MissingPermission")
class ConnectionReceiver(
    private val hidProfileManager: HidProfileManager,
    private val getPreferredDevice: () -> BluetoothDevice?,
    private val onDisconnected: (BluetoothDevice) -> Unit,
) : BroadcastReceiver() {

    companion object {
        private const val TAG = "ConnectionReceiver"

        /** Delay after ACL_CONNECTED before calling HID connect(). See Edge Case #1. */
        private const val ACL_CONNECT_DELAY_MS = 2_500L

        /** Max time to wait for app registration before giving up on this connect attempt. */
        private const val REGISTRATION_AWAIT_TIMEOUT_MS = 10_000L

        /**
         * Only auto-connect HID to devices that can actually act as a HID host
         * (PCs, phones/tablets, TVs/set-top boxes). Prevents hijacking the single HID
         * slot with headphones, watches, car kits, etc.
         */
        @SuppressLint("MissingPermission")
        private fun isLikelyHidHost(device: BluetoothDevice): Boolean {
            val btClass = try { device.bluetoothClass } catch (_: SecurityException) { null } ?: return true
            return when (btClass.majorDeviceClass) {
                BluetoothClass.Device.Major.COMPUTER,
                BluetoothClass.Device.Major.PHONE,
                BluetoothClass.Device.Major.MISC,
                BluetoothClass.Device.Major.UNCATEGORIZED -> true
                BluetoothClass.Device.Major.AUDIO_VIDEO -> when (btClass.deviceClass) {
                    BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER,
                    BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR,
                    BluetoothClass.Device.AUDIO_VIDEO_SET_TOP_BOX,
                    BluetoothClass.Device.AUDIO_VIDEO_VIDEO_GAMING_TOY -> true
                    else -> false
                }
                else -> false
            }
        }
    }

    // Coroutine scope for the delayed connect dispatched on IO thread.
    // SupervisorJob ensures one failed connect doesn't cancel pending reconnects.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {

            // ──────────────────────────────────────────────────────────────
            // ACL Connected — Edge Case Fix #1
            // ──────────────────────────────────────────────────────────────
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                @Suppress("DEPRECATION")
                val device: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    ?: return
                val preferred = getPreferredDevice()

                Log.i(TAG, "ACTION_ACL_CONNECTED: device=${device.address}, preferred=${preferred?.address}")

                // Strict Auto-Connect policy: ONLY initiate an outgoing HID connection when a
                // device is explicitly set as the preferred / auto-connect target. If no device
                // is selected for auto-connect, do not connect automatically.
                val shouldConnect = preferred != null &&
                        device.address.equals(preferred.address, ignoreCase = true) &&
                        isLikelyHidHost(device)

                if (shouldConnect) {
                    scope.launch {
                        // FIX: Give a host-initiated connection time to open the HID control /
                        // interrupt channels itself. Calling connect() too early collides with
                        // the incoming L2CAP setup and both attempts fail.
                        Log.i(TAG, "Host ACL connected — waiting ${ACL_CONNECT_DELAY_MS}ms for host-initiated HID channel")
                        delay(ACL_CONNECT_DELAY_MS)

                        val registered = withTimeoutOrNull(REGISTRATION_AWAIT_TIMEOUT_MS) {
                            hidProfileManager.isAppRegistered.first { it }
                        }

                        if (registered == null) {
                            Log.e(TAG, "Timed out waiting for HID app registration; aborting connect")
                            return@launch
                        }

                        val currentState = try {
                            hidProfileManager.hidDevice?.getConnectionState(device)
                        } catch (_: Exception) { null }

                        if (currentState != android.bluetooth.BluetoothProfile.STATE_CONNECTED &&
                            currentState != android.bluetooth.BluetoothProfile.STATE_CONNECTING) {
                            Log.i(TAG, "ACL connected and HID L2CAP not active yet — calling connectToDevice(${device.address})")
                            hidProfileManager.connectToDevice(device)
                        } else {
                            Log.i(TAG, "ACL connected and HID L2CAP already active/connecting ($currentState) — skipping duplicate connect()")
                        }
                    }
                }
            }

            // ──────────────────────────────────────────────────────────────
            // ACL Disconnected — trigger service reconnect loop
            // ──────────────────────────────────────────────────────────────
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                @Suppress("DEPRECATION")
                val device: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    ?: return
                Log.i(TAG, "ACTION_ACL_DISCONNECTED: device=${device.address}")
                onDisconnected(device)
            }

            // ──────────────────────────────────────────────────────────────
            // Bond state changed — log pairing/unpairing event
            // ──────────────────────────────────────────────────────────────
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                @Suppress("DEPRECATION")
                val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                Log.i(TAG, "ACTION_BOND_STATE_CHANGED: device=${device?.address}, bondState=$bondState")
            }

            // ──────────────────────────────────────────────────────────────
            // Bluetooth adapter state changed — Edge Case Fix #3
            // ──────────────────────────────────────────────────────────────
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                Log.i(TAG, "ACTION_STATE_CHANGED: state=$state")

                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        // BT was re-enabled — HID app registration is wiped by the stack.
                        // Re-acquire the profile proxy and re-register.
                        Log.i(TAG, "Bluetooth turned ON — triggering HID app re-registration")
                        hidProfileManager.reRegisterApp(context)
                    }
                    BluetoothAdapter.STATE_TURNING_OFF,
                    BluetoothAdapter.STATE_OFF -> {
                        // Profile proxy will disconnect; HidProfileManager.onServiceDisconnected
                        // handles cleanup. Just log here.
                        Log.i(TAG, "Bluetooth turning off — profile proxy will be invalidated")
                    }
                }
            }
        }
    }
}
