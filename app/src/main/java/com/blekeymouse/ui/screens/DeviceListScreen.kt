package com.blekeymouse.ui.screens

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blekeymouse.data.ConnectionState
import com.blekeymouse.ui.components.DeviceCard
import com.blekeymouse.ui.components.DeviceCategory
import com.blekeymouse.ui.components.ServiceToggle
import com.blekeymouse.ui.components.StatusBadge
import com.blekeymouse.ui.theme.BorderSubtle
import com.blekeymouse.ui.theme.CyanGlow
import com.blekeymouse.ui.theme.DeepNavy
import com.blekeymouse.ui.theme.RedError
import com.blekeymouse.ui.theme.SurfaceDark
import com.blekeymouse.ui.theme.SurfaceElevated
import com.blekeymouse.ui.theme.TextPrimary
import com.blekeymouse.ui.theme.TextSecondary
import com.blekeymouse.viewmodel.MainViewModel

/**
 * Device List Screen — Material 3 dashboard for the serverless Bluetooth HID peripheral.
 *
 * Shows:
 *  • Serverless HID Peripheral hero card (status, active capabilities: Mouse + Keyboard + Air Mouse,
 *    Quick Settings tile & background behavior summary)
 *  • Foreground HID Service power toggle
 *  • Single-choice Auto-Connect policy card (including "None — Manual connect only")
 *  • Paired Bluetooth devices with category badges, exclusive Auto-Connect radio selection,
 *    Connect/Disconnect, and Unpair confirmation dialog
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(viewModel: MainViewModel) {
    val pairedDevices by viewModel.pairedDevices.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val isServiceRunning by viewModel.isServiceRunning.collectAsStateWithLifecycle()
    val autoConnectAddress by viewModel.autoConnectDeviceAddress.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var devicePendingUnpair by remember { mutableStateOf<BluetoothDevice?>(null) }

    var listVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.refreshPairedDevices()
        listVisible = true
    }

    val connectedAddress = (connectionState as? ConnectionState.Connected)?.deviceAddress

    // Sort HID host candidates (PC, Phone, TV) before audio/wearable accessories
    val sortedDevices = remember(pairedDevices) {
        pairedDevices.sortedByDescending { classifyDevice(it).isHidHostCandidate }
    }

    val autoConnectDeviceName = remember(pairedDevices, autoConnectAddress) {
        if (autoConnectAddress.isNullOrBlank()) null
        else pairedDevices.firstOrNull { it.address.equals(autoConnectAddress, ignoreCase = true) }
            ?.let { safeDeviceName(it) } ?: autoConnectAddress
    }

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
                            text = "Serverless Bluetooth HID Mouse • Touchpad • Air Mouse",
                            style = MaterialTheme.typography.labelMedium,
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DeepNavy)
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
            // ── Serverless HID Peripheral Status Card ────────────────────
            item {
                AnimatedVisibility(
                    visible = listVisible,
                    enter = fadeIn(tween(250)) + slideInVertically(tween(250)) { -20 }
                ) {
                    PeripheralHeroCard(
                        connectionState = connectionState,
                        autoConnectDeviceName = autoConnectDeviceName,
                        onMakeDiscoverable = {
                            try {
                                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                                    putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                openBluetoothSettings(context)
                            }
                        },
                        onOpenBluetoothSettings = { openBluetoothSettings(context) }
                    )
                }
            }

            // ── Service Toggle ───────────────────────────────────────────
            item {
                AnimatedVisibility(
                    visible = listVisible,
                    enter = fadeIn(tween(320)) + slideInVertically(tween(320)) { -20 }
                ) {
                    ServiceToggle(
                        isRunning = isServiceRunning,
                        onToggle = { viewModel.toggleService() }
                    )
                }
            }

            // ── Auto-Connect Policy Card ("None" vs 1 Selected Host) ─────
            item {
                AnimatedVisibility(
                    visible = listVisible,
                    enter = fadeIn(tween(380)) + slideInVertically(tween(380)) { -15 }
                ) {
                    AutoConnectPolicyCard(
                        autoConnectDeviceName = autoConnectDeviceName,
                        onSelectNone = { viewModel.clearAutoConnectDevice() }
                    )
                }
            }

            // ── Paired Devices Section Header ────────────────────────────
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.BluetoothSearching,
                            contentDescription = null,
                            tint = CyanGlow,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Paired Devices (${sortedDevices.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    }
                    Text(
                        text = "Choose 1 for Auto-Connect",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }

            // ── Device List ──────────────────────────────────────────────
            if (sortedDevices.isEmpty()) {
                item {
                    EmptyDevicesPlaceholder(
                        onOpenSettings = { openBluetoothSettings(context) }
                    )
                }
            } else {
                itemsIndexed(
                    items = sortedDevices,
                    key = { _, device -> device.address }
                ) { index, device ->
                    AnimatedVisibility(
                        visible = listVisible,
                        enter = fadeIn(tween(260 + index * 60)) +
                                slideInVertically(tween(260 + index * 60)) { 24 }
                    ) {
                        val isAutoConnectTarget = device.address.equals(autoConnectAddress, ignoreCase = true)
                        val isActive = device.address.equals(connectedAddress, ignoreCase = true) ||
                                (isAutoConnectTarget && (connectionState is ConnectionState.Connecting || connectionState is ConnectionState.Error))
                        val category = classifyDevice(device)

                        DeviceCard(
                            deviceName = safeDeviceName(device),
                            deviceAddress = device.address,
                            category = category,
                            connectionState = if (isActive) connectionState else ConnectionState.Disconnected,
                            isThisDeviceActive = isActive,
                            isAutoConnectTarget = isAutoConnectTarget,
                            onAutoConnectSelect = {
                                // Toggle off if already selected, otherwise select exclusively
                                viewModel.setAutoConnectDevice(device.address, !isAutoConnectTarget)
                            },
                            onConnectClick = { viewModel.connectTo(device) },
                            onDisconnectClick = { viewModel.disconnect() },
                            onUnpairClick = { devicePendingUnpair = device }
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }

    // ── Unpair Confirmation Dialog ───────────────────────────────────────────
    devicePendingUnpair?.let { targetDevice ->
        val name = safeDeviceName(targetDevice)
        AlertDialog(
            onDismissRequest = { devicePendingUnpair = null },
            containerColor = SurfaceElevated,
            icon = {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = null,
                    tint = RedError
                )
            },
            title = {
                Text(
                    text = "Unpair $name?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    text = "This removes the Bluetooth bond with $name (${targetDevice.address}). To reconnect later, you will need to pair again from the host device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        devicePendingUnpair = null
                        val removed = viewModel.unpairDevice(targetDevice)
                        if (removed) {
                            Toast.makeText(context, "Unpaired $name", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(
                                context,
                                "Opening Bluetooth settings to forget $name",
                                Toast.LENGTH_LONG
                            ).show()
                            openBluetoothSettings(context)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RedError,
                        contentColor = TextPrimary
                    )
                ) {
                    Text("Unpair", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { devicePendingUnpair = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Serverless HID Peripheral Hero Card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PeripheralHeroCard(
    connectionState: ConnectionState,
    autoConnectDeviceName: String?,
    onMakeDiscoverable: () -> Unit,
    onOpenBluetoothSettings: () -> Unit
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, BorderSubtle)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(SurfaceDark, SurfaceElevated.copy(alpha = 0.65f))
                    )
                )
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "HID PERIPHERAL ROLE",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    fontWeight = FontWeight.Bold,
                    color = CyanGlow
                )
                StatusBadge(connectionState = connectionState)
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = connectionState.message,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (autoConnectDeviceName != null)
                    "Auto-Connect target: $autoConnectDeviceName (connects in foreground & background)"
                else
                    "Auto-Connect: Off — app stays in standby until you tap Connect",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Capability badges
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CapabilityBadge(icon = Icons.Rounded.Mouse, label = "16-bit Touchpad")
                CapabilityBadge(icon = Icons.Rounded.Sensors, label = "100Hz Air Mouse")
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                FilledTonalButton(
                    onClick = onMakeDiscoverable,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = CyanGlow.copy(alpha = 0.16f),
                        contentColor = CyanGlow
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Visibility,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Make Discoverable",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                OutlinedButton(
                    onClick = onOpenBluetoothSettings,
                    border = BorderStroke(1.dp, BorderSubtle),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Bluetooth,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Pair New Host",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun CapabilityBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Surface(
        color = SurfaceElevated,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, BorderSubtle.copy(alpha = 0.7f))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = CyanGlow,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = TextSecondary
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Auto-Connect Policy Card ("None — Do not auto-connect" option)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AutoConnectPolicyCard(
    autoConnectDeviceName: String?,
    onSelectNone: () -> Unit
) {
    val noneSelected = autoConnectDeviceName == null
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, BorderSubtle)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.AutoMode,
                    contentDescription = null,
                    tint = CyanGlow,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Startup & Background Auto-Connect",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onSelectNone)
                    .padding(vertical = 2.dp)
            ) {
                RadioButton(
                    selected = noneSelected,
                    onClick = onSelectNone,
                    colors = RadioButtonDefaults.colors(
                        selectedColor = CyanGlow,
                        unselectedColor = TextSecondary
                    ),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "None — Do not auto-connect (manual connect only)",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (noneSelected) CyanGlow else TextSecondary,
                    fontWeight = if (noneSelected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Empty Placeholder
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EmptyDevicesPlaceholder(onOpenSettings: () -> Unit) {
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
            text = "Tap 'Make Discoverable' above and pair from your PC, Phone, or TV",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onOpenSettings,
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

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun openBluetoothSettings(context: android.content.Context) {
    try {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    } catch (_: Exception) {}
}

@SuppressLint("MissingPermission")
private fun safeDeviceName(device: BluetoothDevice): String =
    try { device.name ?: device.address } catch (_: SecurityException) { device.address }

@SuppressLint("MissingPermission")
private fun classifyDevice(device: BluetoothDevice): DeviceCategory {
    val btClass = try { device.bluetoothClass } catch (_: SecurityException) { null }
        ?: return DeviceCategory.OTHER
    return when (btClass.majorDeviceClass) {
        BluetoothClass.Device.Major.COMPUTER -> DeviceCategory.COMPUTER
        BluetoothClass.Device.Major.PHONE -> DeviceCategory.PHONE
        BluetoothClass.Device.Major.WEARABLE -> DeviceCategory.WEARABLE
        BluetoothClass.Device.Major.AUDIO_VIDEO -> when (btClass.deviceClass) {
            BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER,
            BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR,
            BluetoothClass.Device.AUDIO_VIDEO_SET_TOP_BOX,
            BluetoothClass.Device.AUDIO_VIDEO_VIDEO_GAMING_TOY -> DeviceCategory.TV
            else -> DeviceCategory.AUDIO
        }
        else -> DeviceCategory.OTHER
    }
}
