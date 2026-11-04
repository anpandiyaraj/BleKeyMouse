package com.blekeymouse.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blekeymouse.data.ConnectionState
import com.blekeymouse.ui.theme.BorderSubtle
import com.blekeymouse.ui.theme.CyanGlow
import com.blekeymouse.ui.theme.GradientEnd
import com.blekeymouse.ui.theme.GradientStart
import com.blekeymouse.ui.theme.GreenConnected
import com.blekeymouse.ui.theme.SurfaceElevated
import com.blekeymouse.ui.theme.TextSecondary

import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults

/**
 * Card representing a single paired Bluetooth device.
 *
 * Shows the device name, MAC address, connection state badge,
 * an Auto-Connect option checkbox, and a "Connect" button.
 */
@Composable
fun DeviceCard(
    deviceName: String,
    deviceAddress: String,
    connectionState: ConnectionState,
    isThisDeviceActive: Boolean,
    isAutoConnectTarget: Boolean,
    onAutoConnectToggle: (Boolean) -> Unit,
    onConnectClick: () -> Unit,
    onDisconnectClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val cardAlpha by animateFloatAsState(
        targetValue = if (isThisDeviceActive || isAutoConnectTarget) 1f else 0.85f,
        animationSpec = tween(300),
        label = "cardAlpha"
    )

    val borderBrush = if (isThisDeviceActive && connectionState is ConnectionState.Connected) {
        Brush.linearGradient(listOf(GradientStart, GradientEnd))
    } else if (isThisDeviceActive && connectionState is ConnectionState.Error) {
        Brush.linearGradient(listOf(com.blekeymouse.ui.theme.RedError, BorderSubtle))
    } else if (isAutoConnectTarget) {
        Brush.linearGradient(listOf(CyanGlow.copy(alpha = 0.5f), BorderSubtle))
    } else {
        Brush.linearGradient(listOf(BorderSubtle, BorderSubtle))
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .alpha(cardAlpha)
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceElevated)
            .border(
                width = if (isThisDeviceActive || isAutoConnectTarget) 1.5.dp else 1.dp,
                brush = borderBrush,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(16.dp)
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // ── Device Icon ──────────────────────────────────────────────
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (isThisDeviceActive)
                                Brush.radialGradient(listOf(CyanGlow.copy(alpha = 0.2f), CyanGlow.copy(alpha = 0f)))
                            else
                                Brush.radialGradient(listOf(BorderSubtle.copy(alpha = 0.3f), BorderSubtle.copy(alpha = 0f)))
                        )
                ) {
                    Icon(
                        imageVector = when {
                            connectionState is ConnectionState.Connected && isThisDeviceActive ->
                                Icons.Rounded.BluetoothConnected
                            connectionState is ConnectionState.Disconnected ->
                                Icons.Rounded.BluetoothDisabled
                            else -> Icons.Rounded.Bluetooth
                        },
                        contentDescription = null,
                        tint = if (isThisDeviceActive || isAutoConnectTarget) CyanGlow else TextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                // ── Device Info ──────────────────────────────────────────────
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = deviceName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = deviceAddress,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    StatusBadge(connectionState = if (isThisDeviceActive) connectionState else ConnectionState.Disconnected)
                }

                Spacer(modifier = Modifier.width(12.dp))

                // ── Connect / Disconnect Action Button ───────────────────────
                if (isThisDeviceActive && (connectionState is ConnectionState.Connected || connectionState is ConnectionState.Connecting)) {
                    Button(
                        onClick = { onDisconnectClick?.invoke() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = com.blekeymouse.ui.theme.RedError.copy(alpha = 0.15f),
                            contentColor = com.blekeymouse.ui.theme.RedError
                        ),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            text = "Disconnect",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else {
                    Button(
                        onClick = onConnectClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyanGlow.copy(alpha = 0.15f),
                            contentColor = CyanGlow
                        ),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            text = if (isThisDeviceActive && connectionState is ConnectionState.Error) "Retry" else "Connect",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ── Auto Connect Option Box ────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = isAutoConnectTarget,
                    onCheckedChange = onAutoConnectToggle,
                    colors = CheckboxDefaults.colors(
                        checkedColor = CyanGlow,
                        uncheckedColor = BorderSubtle,
                        checkmarkColor = MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isAutoConnectTarget) "Auto-Connect Enabled (Cold Start)" else "Enable Auto-Connect on startup",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isAutoConnectTarget) CyanGlow else TextSecondary,
                    fontWeight = if (isAutoConnectTarget) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}
