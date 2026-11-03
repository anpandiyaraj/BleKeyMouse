package com.blekeymouse.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Centralizes all Bluetooth runtime permission logic.
 *
 * Android has two distinct permission models for Bluetooth:
 *
 *  • SDK < 31 (Android 10/11):
 *    [Manifest.permission.BLUETOOTH] + [Manifest.permission.BLUETOOTH_ADMIN]
 *    + [Manifest.permission.ACCESS_FINE_LOCATION] (required for device discovery)
 *
 *  • SDK ≥ 31 (Android 12+):
 *    [Manifest.permission.BLUETOOTH_CONNECT] + [Manifest.permission.BLUETOOTH_SCAN]
 *    (BLUETOOTH_ADVERTISE needed only if actively advertising)
 *
 * Note: [Manifest.permission.POST_NOTIFICATIONS] is also required on SDK ≥ 33
 * to display the foreground service notification.
 */
object BluetoothPermissionHelper {

    /**
     * Returns the exact set of permissions that must be granted for this SDK version.
     * Use this list with [ActivityResultContracts.RequestMultiplePermissions].
     */
    fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ — modern permission set
            buildList {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // Android 13+ — POST_NOTIFICATIONS for foreground service notification
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }.toTypedArray()
        } else {
            // Android 10/11 — legacy permission set
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }

    /**
     * Returns true if all required permissions are currently granted.
     * Check this before starting the service or calling any BT APIs.
     */
    fun hasAllPermissions(context: Context): Boolean {
        return requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Returns only the permissions from [requiredPermissions] that are NOT yet granted.
     * Use this to show a targeted rationale dialog to the user.
     */
    fun missingPermissions(context: Context): List<String> {
        return requiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) !=
                    PackageManager.PERMISSION_GRANTED
        }
    }
}
