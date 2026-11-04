package com.blekeymouse.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blekeymouse.data.ConnectionState
import com.blekeymouse.ui.theme.BorderSubtle
import com.blekeymouse.ui.theme.CyanGlow
import com.blekeymouse.ui.theme.DeepNavy
import com.blekeymouse.ui.theme.GradientEnd
import com.blekeymouse.ui.theme.GradientStart
import com.blekeymouse.ui.theme.RedError
import com.blekeymouse.ui.theme.SurfaceElevated
import com.blekeymouse.ui.theme.TextPrimary
import com.blekeymouse.ui.theme.TextSecondary

/**
 * High-level category of a paired Bluetooth device, inferred from its `BluetoothClass`.
 */
enum class DeviceCategory(val label: String, val icon: ImageVector, val isHidHostCandidate: Boolean) {
    COMPUTER("PC / Mac", Icons.Rounded.Computer, true),
    PHONE("Phone / Tablet", Icons.Rounded.Smartphone, true),
    TV("TV / Display", Icons.Rounded.Tv, true),
    AUDIO("Audio Accessory", Icons.Rounded.Headphones, false),
    WEARABLE("Wearable", Icons.Rounded.Watch, false),
    OTHER("Bluetooth Device", Icons.Rounded.Bluetooth, true)
}

/**
 * Material 3 card representing a single paired Bluetooth device.
 *
 * Features:
 *  • Device category icon + role badge (PC/Mac, Phone/Tablet, TV, etc.)
 *  • Live [StatusBadge] when active
 *  • Single-choice [RadioButton] to select this device as the exclusive Auto-Connect host
 *  • Connect / Disconnect action button
 *  • Unpair (remove bond) icon button
 */
@Composable
fun DeviceCard(
    deviceName: String,
    deviceAddress: String,
    category: DeviceCategory,
    connectionState: ConnectionState,
    isThisDeviceActive: Boolean,
    isAutoConnectTarget: Boolean,
    onAutoConnectSelect: () -> Unit,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onUnpairClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardAlpha by animateFloatAsState(
        targetValue = if (isThisDeviceActive || isAutoConnectTarget) 1f else 0.90f,
        animationSpec = tween(250),
        label = "cardAlpha"
    )

    val borderBrush = when {
        isThisDeviceActive && connectionState is ConnectionState.Connected ->
            Brush.linearGradient(listOf(GradientStart, GradientEnd))
        isThisDeviceActive && connectionState is ConnectionState.Error ->
            Brush.linearGradient(listOf(RedError, BorderSubtle))
        isAutoConnectTarget ->
            Brush.linearGradient(listOf(CyanGlow.copy(alpha = 0.65f), BorderSubtle))
        else ->
            Brush.linearGradient(listOf(BorderSubtle, BorderSubtle))
    }

    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .alpha(cardAlpha),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = SurfaceElevated),
        border = BorderStroke(
            width = if (isThisDeviceActive || isAutoConnectTarget) 1.5.dp else 1.dp,
            brush = borderBrush
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // ── Device Category Icon ───────────────────────────────────
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (isThisDeviceActive || isAutoConnectTarget)
                                CyanGlow.copy(alpha = 0.14f)
                            else
                                BorderSubtle.copy(alpha = 0.35f)
                        )
                ) {
                    Icon(
                        imageVector = if (isThisDeviceActive && connectionState is ConnectionState.Connected)
                            Icons.Rounded.BluetoothConnected
                        else
                            category.icon,
                        contentDescription = category.label,
                        tint = if (isThisDeviceActive || isAutoConnectTarget) CyanGlow else TextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // ── Device Name, Category Chip & Status ────────────────────
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = deviceName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            color = BorderSubtle.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = category.label,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = TextSecondary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            text = deviceAddress,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary.copy(alpha = 0.75f),
                            maxLines = 1
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    StatusBadge(
                        connectionState = if (isThisDeviceActive) connectionState else ConnectionState.Disconnected
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // ── Connect / Disconnect + Unpair Actions ──────────────────
                if (isThisDeviceActive && (connectionState is ConnectionState.Connected || connectionState is ConnectionState.Connecting)) {
                    FilledTonalButton(
                        onClick = onDisconnectClick,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = RedError.copy(alpha = 0.16f),
                            contentColor = RedError
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.LinkOff,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Disconnect",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else {
                    FilledTonalButton(
                        onClick = onConnectClick,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = CyanGlow.copy(alpha = 0.16f),
                            contentColor = CyanGlow
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (isThisDeviceActive && connectionState is ConnectionState.Error) "Retry" else "Connect",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                IconButton(
                    onClick = onUnpairClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Unpair $deviceName",
                        tint = TextSecondary,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = BorderSubtle.copy(alpha = 0.45f))
            Spacer(modifier = Modifier.height(6.dp))

            // ── Exclusive Auto-Connect Radio Row ───────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onAutoConnectSelect)
                    .padding(vertical = 2.dp)
            ) {
                RadioButton(
                    selected = isAutoConnectTarget,
                    onClick = onAutoConnectSelect,
                    colors = RadioButtonDefaults.colors(
                        selectedColor = CyanGlow,
                        unselectedColor = TextSecondary
                    ),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isAutoConnectTarget)
                            "Selected for Auto-Connect (Foreground & Background)"
                        else
                            "Set as Auto-Connect Host",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isAutoConnectTarget) CyanGlow else TextSecondary,
                        fontWeight = if (isAutoConnectTarget) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
                if (isAutoConnectTarget) {
                    Surface(
                        color = CyanGlow.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "DEFAULT",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            fontWeight = FontWeight.Bold,
                            color = CyanGlow,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
