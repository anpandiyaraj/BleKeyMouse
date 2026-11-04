package com.blekeymouse.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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
        private const val ACL_CONNECT_DELAY_MS = 500L

        /** Max time to wait for app registration before giving up on this connect attempt. */
        private const val REGISTRATION_AWAIT_TIMEOUT_MS = 10_000L
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
                val device: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    ?: return
                val preferred = getPreferredDevice()

                Log.i(TAG, "ACTION_ACL_CONNECTED: device=${device.address}, preferred=${preferred?.address}")

                // Connect if this matches preferred device, or if preferred is null but device is bonded
                val shouldConnect = (preferred != null && device.address.equals(preferred.address, ignoreCase = true)) ||
                        (preferred == null && device.bondState == BluetoothDevice.BOND_BONDED)

                if (shouldConnect) {
                    scope.launch {
                        Log.i(TAG, "Preferred device ACL connected — waiting ${ACL_CONNECT_DELAY_MS}ms for baseband settle")
                        delay(ACL_CONNECT_DELAY_MS) // baseband settle — Edge Case #1

                        // Gate: ensure HID app is registered before connecting — Edge Case #2
                        val registered = withTimeoutOrNull(REGISTRATION_AWAIT_TIMEOUT_MS) {
                            hidProfileManager.isAppRegistered.first { it }
                        }

                        if (registered == null) {
                            Log.e(TAG, "Timed out waiting for HID app registration; aborting connect")
                            return@launch
                        }

                        Log.i(TAG, "HID app registered; calling connect() on ${device.address}")
                        hidProfileManager.connectToDevice(device)
                    }
                }
            }

            // ──────────────────────────────────────────────────────────────
            // ACL Disconnected — trigger service reconnect loop
            // ──────────────────────────────────────────────────────────────
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                val device: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    ?: return
                Log.i(TAG, "ACTION_ACL_DISCONNECTED: device=${device.address}")
                onDisconnected(device)
            }

            // ──────────────────────────────────────────────────────────────
            // Bond state changed — log pairing/unpairing event
            // ──────────────────────────────────────────────────────────────
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
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
