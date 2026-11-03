package com.blekeymouse.util

import android.bluetooth.BluetoothDevice
import android.os.Build
import com.blekeymouse.data.ConnectionState

/**
 * Kotlin extension functions used throughout the codebase.
 */

/** Safe display name: prefers [BluetoothDevice.getName], falls back to MAC address. */
@androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
fun BluetoothDevice.displayName(): String =
    try {
        name?.takeIf { it.isNotBlank() } ?: address
    } catch (e: SecurityException) {
        address
    }

/** Returns a human-readable bond state string for debugging. */
fun BluetoothDevice.bondStateLabel(): String = when (bondState) {
    BluetoothDevice.BOND_NONE    -> "Not Paired"
    BluetoothDevice.BOND_BONDING -> "Pairing…"
    BluetoothDevice.BOND_BONDED  -> "Paired"
    else                          -> "Unknown"
}

/** Returns true if this [ConnectionState] represents an active (connected) session. */
val ConnectionState.isConnected: Boolean
    get() = this is ConnectionState.Connected

/** Returns true if this [ConnectionState] represents an in-progress attempt. */
val ConnectionState.isConnecting: Boolean
    get() = this is ConnectionState.Connecting || this is ConnectionState.Registering

/** Convenience: current SDK is Android 12 or higher. */
val isAtLeastS: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** Convenience: current SDK is Android 13 or higher. */
val isAtLeastT: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/** Convenience: current SDK is Android 14 or higher. */
val isAtLeastU: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
