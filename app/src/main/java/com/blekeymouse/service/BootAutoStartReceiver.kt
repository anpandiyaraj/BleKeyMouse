package com.blekeymouse.service

import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.blekeymouse.di.AppModule
import com.blekeymouse.util.BluetoothPermissionHelper

/**
 * Manifest-registered [BroadcastReceiver] that starts [BluetoothHidService] in the background
 * after device reboot or app update — **only** when the user has selected a paired device for
 * Auto-Connect and Bluetooth permissions are granted.
 *
 * Why this works on Android 14/15 (API 34+):
 *   `ACTION_BOOT_COMPLETED` and `ACTION_MY_PACKAGE_REPLACED` are on Android's background-start
 *   exemption list for `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` when the app holds
 *   `BLUETOOTH_CONNECT`.
 */
class BootAutoStartReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootAutoStartReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        if (!BluetoothPermissionHelper.hasAllPermissions(context)) {
            Log.i(TAG, "onReceive($action): Bluetooth permissions not granted — skipping background start")
            return
        }

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (btManager?.adapter?.isEnabled != true) {
            Log.i(TAG, "onReceive($action): Bluetooth is off — skipping background start")
            return
        }

        val repo = AppModule.provideRepository(context.applicationContext)
        if (repo.isAutoConnectEnabled() && !repo.getAutoConnectDeviceAddress().isNullOrBlank()) {
            Log.i(TAG, "onReceive($action): Auto-Connect device configured — starting HID service in background")
            repo.autoConnectIfPossible()
        } else {
            Log.i(TAG, "onReceive($action): No Auto-Connect device selected — staying idle")
        }
    }
}

