package com.blekeymouse.service

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.blekeymouse.MainActivity
import com.blekeymouse.data.ConnectionState
import com.blekeymouse.di.AppModule
import com.blekeymouse.util.BluetoothPermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Android Quick Settings Tile ("BleKeyMouse").
 *
 * Allows the user to start/stop the serverless Bluetooth HID Mouse & Keyboard service and
 * trigger Auto-Connect directly from the notification shade without opening the full UI.
 * Long-pressing the tile opens [MainActivity].
 */
class HidQuickSettingsTileService : TileService() {

    private var tileScope: CoroutineScope? = null
    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val repo = AppModule.provideRepository(applicationContext)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        tileScope = scope
        stateJob = scope.launch {
            combine(repo.isServiceRunning, repo.getConnectionStateFlow()) { running, state ->
                running to state
            }.collect { (running, state) ->
                updateTileUi(running, state)
            }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        stateJob = null
        tileScope?.cancel()
        tileScope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (!BluetoothPermissionHelper.hasAllPermissions(this)) {
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivityAndCollapse(launchIntent)
            return
        }

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (btManager?.adapter?.isEnabled != true) {
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivityAndCollapse(launchIntent)
            return
        }

        val repo = AppModule.provideRepository(applicationContext)
        if (repo.isServiceRunning.value) {
            repo.stopService()
        } else {
            repo.autoConnectIfPossible()
        }
    }

    private fun updateTileUi(running: Boolean, state: ConnectionState) {
        val tile = qsTile ?: return
        tile.label = "BleKeyMouse"
        when {
            !running -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "Off"
            }
            state is ConnectionState.Connected -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = state.deviceName
            }
            state is ConnectionState.Connecting -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "Connecting…"
            }
            else -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "Standby"
            }
        }
        tile.updateTile()
    }
}

