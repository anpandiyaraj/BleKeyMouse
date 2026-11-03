package com.blekeymouse

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.blekeymouse.di.AppModule
import com.blekeymouse.ui.screens.MainScreen
import com.blekeymouse.ui.theme.BleKeyMouseTheme
import com.blekeymouse.ui.theme.CyanGlow
import com.blekeymouse.ui.theme.DeepNavy
import com.blekeymouse.ui.theme.GrayMuted
import com.blekeymouse.ui.theme.TextSecondary
import com.blekeymouse.util.BluetoothPermissionHelper
import com.blekeymouse.viewmodel.MainViewModel

/**
 * Single-activity host for the BleKeyMouse application.
 *
 * Responsibilities:
 *  1. Checks and requests Bluetooth runtime permissions (SDK-fork logic
 *     delegated to [BluetoothPermissionHelper]).
 *  2. Checks that Bluetooth is enabled; prompts to enable if not.
 *  3. Provides the [MainViewModel] with the repository singleton.
 *  4. Hosts the Compose NavHost via [MainScreen].
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var viewModel: MainViewModel

    // Current UI state — whether all permissions are granted
    private var permissionsGranted by mutableStateOf(false)
    private var bluetoothEnabled  by mutableStateOf(false)

    // ─── Permission Request Launcher ─────────────────────────────────────────
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        Log.i(TAG, "Permission result: allGranted=$allGranted, results=$results")
        permissionsGranted = allGranted
        if (allGranted) checkBluetoothEnabledAndAutoConnect()
    }

    // ─── Enable BT Request Launcher ──────────────────────────────────────────
    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothEnabled = btManager.adapter?.isEnabled == true
        Log.i(TAG, "Enable BT result: bluetoothEnabled=$bluetoothEnabled")
        if (bluetoothEnabled && permissionsGranted) {
            viewModel.autoConnectIfPossible()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = AppModule.provideRepository(applicationContext)
        viewModel = ViewModelProvider(
            this,
            MainViewModel.factory(repository)
        )[MainViewModel::class.java]

        // Check permissions on creation (also handles config change restores)
        permissionsGranted = BluetoothPermissionHelper.hasAllPermissions(this)
        if (permissionsGranted) checkBluetoothEnabledAndAutoConnect()

        setContent {
            BleKeyMouseTheme {
                when {
                    !permissionsGranted -> PermissionRationaleScreen(
                        onRequestPermissions = { requestPermissions() }
                    )
                    !bluetoothEnabled   -> BluetoothDisabledScreen(
                        onEnableBluetooth = { promptEnableBluetooth() }
                    )
                    else                -> MainScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check on resume in case user granted permissions from Settings
        permissionsGranted = BluetoothPermissionHelper.hasAllPermissions(this)
        if (permissionsGranted) checkBluetoothEnabledAndAutoConnect()
    }

    private fun requestPermissions() {
        val missing = BluetoothPermissionHelper.missingPermissions(this)
        Log.i(TAG, "Requesting permissions: $missing")
        permissionLauncher.launch(missing.toTypedArray())
    }

    private fun checkBluetoothEnabledAndAutoConnect() {
        val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothEnabled = btManager.adapter?.isEnabled == true
        Log.i(TAG, "checkBluetoothEnabledAndAutoConnect: $bluetoothEnabled")
        if (bluetoothEnabled && permissionsGranted) {
            viewModel.autoConnectIfPossible()
        }
    }

    private fun promptEnableBluetooth() {
        enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Permission Rationale Screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PermissionRationaleScreen(onRequestPermissions: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepNavy)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.BluetoothDisabled,
            contentDescription = null,
            tint = CyanGlow,
            modifier = Modifier.size(72.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Bluetooth Permissions Required",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "BleKeyMouse needs Bluetooth permissions to register as a HID device and connect to your PC or TV.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onRequestPermissions,
            colors = ButtonDefaults.buttonColors(containerColor = CyanGlow)
        ) {
            Text(
                text = "Grant Permissions",
                color = DeepNavy,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Bluetooth Disabled Screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BluetoothDisabledScreen(onEnableBluetooth: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepNavy)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.BluetoothDisabled,
            contentDescription = null,
            tint = GrayMuted,
            modifier = Modifier.size(72.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Bluetooth is Off",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Please enable Bluetooth to use BleKeyMouse as an HID device.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onEnableBluetooth,
            colors = ButtonDefaults.buttonColors(containerColor = CyanGlow)
        ) {
            Text(
                text = "Enable Bluetooth",
                color = DeepNavy,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
