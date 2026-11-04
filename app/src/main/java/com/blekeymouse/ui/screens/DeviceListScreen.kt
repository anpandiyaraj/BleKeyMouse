package com.blekeymouse.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blekeymouse.data.ConnectionState
import com.blekeymouse.ui.components.DeviceCard
import com.blekeymouse.ui.components.ServiceToggle
import com.blekeymouse.ui.components.StatusBadge
import com.blekeymouse.ui.theme.CyanGlow
import com.blekeymouse.ui.theme.DeepNavy
import com.blekeymouse.ui.theme.SurfaceDark
import com.blekeymouse.ui.theme.TextSecondary
import com.blekeymouse.viewmodel.MainViewModel

/**
 * Device List Screen — the main dashboard of the BleKeyMouse app.
 *
 * Shows:
 *  • Overall connection status header
 *  • Service toggle (on/off switch)
 *  • Paired Bluetooth device list with connect buttons
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(viewModel: MainViewModel) {
    val pairedDevices by viewModel.pairedDevices.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val isServiceRunning by viewModel.isServiceRunning.collectAsStateWithLifecycle()
    val autoConnectAddress by viewModel.autoConnectDeviceAddress.collectAsStateWithLifecycle()

    // Animate items in on first composition and refresh paired devices list
    var listVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.refreshPairedDevices()
        listVisible = true
    }

    val connectedAddress = (connectionState as? ConnectionState.Connected)?.deviceAddress

    Scaffold(
        containerColor = DeepNavy,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "BleKeyMouse",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = CyanGlow
                        )
                        Text(
                            text = "Bluetooth HID Controller",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshPairedDevices() }) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Refresh paired devices",
                            tint = CyanGlow
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DeepNavy
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(DeepNavy)
                .padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── Connection Status Banner ─────────────────────────────────
            item {
                AnimatedVisibility(
                    visible = listVisible,
                    enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { -20 }
                ) {
                    ConnectionBanner(connectionState = connectionState)
                }
            }

            item { Spacer(modifier = Modifier.height(4.dp)) }

            // ── Service Toggle ───────────────────────────────────────────
            item {
                AnimatedVisibility(
                    visible = listVisible,
                    enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { -20 }
                ) {
                    ServiceToggle(
                        isRunning = isServiceRunning,
                        onToggle  = { viewModel.toggleService() }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            // ── Section Header ───────────────────────────────────────────
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.BluetoothSearching,
                            contentDescription = null,
                            tint = CyanGlow,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = "Paired Devices (${pairedDevices.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    val context = LocalContext.current
                    Button(
                        onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                try {
                                    val discoverableIntent = Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                                        putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(discoverableIntent)
                                } catch (_: Exception) {}
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyanGlow.copy(alpha = 0.15f),
                            contentColor = CyanGlow
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Bluetooth,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Pair Device",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // ── Device List ──────────────────────────────────────────────
            if (pairedDevices.isEmpty()) {
                item {
                    EmptyDevicesPlaceholder()
                }
            } else {
                itemsIndexed(
                    items = pairedDevices,
                    key   = { _, device -> device.address }
                ) { index, device ->
                    AnimatedVisibility(
                        visible = listVisible,
                        enter = fadeIn(tween(300 + index * 80)) +
                                slideInVertically(tween(300 + index * 80)) { 30 }
                    ) {
                        val isAutoConnectTarget = device.address.equals(autoConnectAddress, ignoreCase = true)
                        val isActive = device.address == connectedAddress || (isAutoConnectTarget && (connectionState is ConnectionState.Connecting || connectionState is ConnectionState.Error))
                        DeviceCard(
                            deviceName           = try { device.name ?: device.address } catch (_: SecurityException) { device.address },
                            deviceAddress        = device.address,
                            connectionState      = if (isActive) connectionState else ConnectionState.Disconnected,
                            isThisDeviceActive   = isActive,
                            isAutoConnectTarget  = isAutoConnectTarget,
                            onAutoConnectToggle  = { checked ->
                                viewModel.setAutoConnectDevice(device.address, checked)
                            },
                            onConnectClick       = { viewModel.connectTo(device) },
                            onDisconnectClick    = { viewModel.disconnect() }
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(80.dp)) } // bottom nav breathing room
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sub-components
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ConnectionBanner(connectionState: ConnectionState) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.horizontalGradient(
                    listOf(
                        SurfaceDark,
                        SurfaceDark.copy(alpha = 0.7f)
                    )
                ),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Column {
            Text(
                text = "Status",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = connectionState.message,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            StatusBadge(connectionState = connectionState)
        }
    }
}

@Composable
private fun EmptyDevicesPlaceholder() {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.BluetoothSearching,
            contentDescription = null,
            tint = TextSecondary.copy(alpha = 0.4f),
            modifier = Modifier.size(56.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "No paired devices found",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Make phone discoverable or pair host PC/TV in Bluetooth settings",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                try {
                    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    try {
                        val discoverableIntent = Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                            putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(discoverableIntent)
                    } catch (_: Exception) {}
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = CyanGlow),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Bluetooth,
                contentDescription = null,
                tint = DeepNavy,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Open Bluetooth Settings",
                color = DeepNavy,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
