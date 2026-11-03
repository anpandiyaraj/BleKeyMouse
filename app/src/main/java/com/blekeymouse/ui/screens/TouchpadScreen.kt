package com.blekeymouse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blekeymouse.data.ConnectionState
import com.blekeymouse.ui.theme.BorderSubtle
import com.blekeymouse.ui.theme.CyanGlow
import com.blekeymouse.ui.theme.DeepNavy
import com.blekeymouse.ui.theme.GrayMuted
import com.blekeymouse.ui.theme.SurfaceDark
import com.blekeymouse.ui.theme.SurfaceElevated
import com.blekeymouse.ui.theme.TextPrimary
import com.blekeymouse.ui.theme.TextSecondary
import com.blekeymouse.viewmodel.MainViewModel
import kotlin.math.roundToInt

private const val BUTTON_LEFT = 0x01
private const val BUTTON_RIGHT = 0x02

/**
 * Touchpad Screen — primary mouse control surface with support for:
 *  • 1-finger drag → cursor movement
 *  • 1-finger tap → left click
 *  • 2-finger tap → right click
 *  • Scroll strip → vertical scroll
 *  • Dedicated left & right click buttons supporting click & drag hold
 */
@Composable
fun TouchpadScreen(viewModel: MainViewModel) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val isAirMouseActive by viewModel.isAirMouseActive.collectAsStateWithLifecycle()

    val isConnected = connectionState is ConnectionState.Connected
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepNavy)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // ── Header ───────────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Rounded.Mouse,
                contentDescription = null,
                tint = if (isConnected) CyanGlow else GrayMuted,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Touchpad",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (isConnected) TextPrimary else TextSecondary
            )
            Spacer(modifier = Modifier.weight(1f))
            StatusBadge(connectionState = connectionState)
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ── Air Mouse Control Banner ─────────────────────────────────────
        AirMouseBanner(
            isConnected = isConnected,
            isAirMouseActive = isAirMouseActive,
            onToggleAirMouse = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.toggleAirMouse()
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // ── Touchpad Surface ─────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            // Main trackpad area
            TouchpadSurface(
                enabled = isConnected,
                onDelta = { dx, dy -> viewModel.sendMouseMove(dx, dy) },
                onLeftClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.sendLeftClick()
                },
                onRightClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.sendRightClick()
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Scroll strip (right side)
            ScrollStrip(
                enabled = isConnected,
                onScroll = { delta -> viewModel.sendScroll(delta) },
                modifier = Modifier
                    .width(40.dp)
                    .fillMaxHeight()
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Click Buttons ────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ClickButton(
                label = "Left Click",
                enabled = isConnected,
                onButtonDown = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.setMouseButtonState(BUTTON_LEFT, isDown = true)
                },
                onButtonUp = {
                    viewModel.setMouseButtonState(BUTTON_LEFT, isDown = false)
                },
                onClick = {
                    viewModel.sendLeftClick()
                },
                modifier = Modifier.weight(1f)
            )
            ClickButton(
                label = "Right Click",
                enabled = isConnected,
                onButtonDown = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.setMouseButtonState(BUTTON_RIGHT, isDown = true)
                },
                onButtonUp = {
                    viewModel.setMouseButtonState(BUTTON_RIGHT, isDown = false)
                },
                onClick = {
                    viewModel.sendRightClick()
                },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(80.dp)) // bottom nav space
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Touchpad Surface
// ─────────────────────────────────────────────────────────────────────────────

private const val SENSITIVITY_FACTOR = 1.5f

@Composable
private fun TouchpadSurface(
    enabled: Boolean,
    onDelta: (dx: Int, dy: Int) -> Unit,
    onLeftClick: () -> Unit,
    onRightClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var touchIndicator by remember { mutableStateOf<Offset?>(null) }
    var accumX by remember { mutableStateOf(0f) }
    var accumY by remember { mutableStateOf(0f) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (enabled)
                    Brush.radialGradient(
                        colors = listOf(SurfaceElevated, SurfaceDark),
                        radius = 800f
                    )
                else
                    Brush.linearGradient(listOf(SurfaceDark.copy(alpha = 0.5f), SurfaceDark.copy(alpha = 0.5f)))
            )
            .border(
                width = 1.dp,
                brush = if (enabled)
                    Brush.linearGradient(listOf(CyanGlow.copy(alpha = 0.3f), BorderSubtle))
                else
                    Brush.linearGradient(listOf(BorderSubtle, BorderSubtle)),
                shape = RoundedCornerShape(20.dp)
            )
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onTap = {
                        onLeftClick()
                    }
                )
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset ->
                        touchIndicator = offset
                        accumX = 0f
                        accumY = 0f
                    },
                    onDragEnd = {
                        touchIndicator = null
                        accumX = 0f
                        accumY = 0f
                    },
                    onDragCancel = {
                        touchIndicator = null
                        accumX = 0f
                        accumY = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val (scaledX, scaledY) = com.blekeymouse.util.PointerAcceleration.scaleDelta(dragAmount.x, dragAmount.y)
                        accumX += scaledX
                        accumY += scaledY

                        val sendX = accumX.toInt()
                        val sendY = accumY.toInt()

                        if (sendX != 0 || sendY != 0) {
                            accumX -= sendX
                            accumY -= sendY
                            onDelta(sendX, sendY)
                        }
                        touchIndicator = change.position
                    }
                )
            }
    ) {
        // Touch ripple indicator
        touchIndicator?.let { pos ->
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .offset(
                        x = (pos.x - 14).dp,
                        y = (pos.y - 14).dp
                    )
                    .clip(CircleShape)
                    .background(CyanGlow.copy(alpha = 0.25f))
            )
        }

        // Center label when idle
        if (touchIndicator == null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = Icons.Rounded.TouchApp,
                    contentDescription = null,
                    tint = if (enabled) CyanGlow.copy(alpha = 0.3f) else GrayMuted.copy(alpha = 0.3f),
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (enabled) "Drag cursor • Tap = Click • 2 fingers = Right click" else "Not connected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) CyanGlow.copy(alpha = 0.4f) else GrayMuted.copy(alpha = 0.4f)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Scroll Strip
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ScrollStrip(
    enabled: Boolean,
    onScroll: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderSubtle, RoundedCornerShape(20.dp))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val scrollDelta = -(dragAmount.y / 30f).roundToInt() // invert: up = positive scroll
                    if (scrollDelta != 0) onScroll(scrollDelta)
                }
            }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            repeat(5) { index ->
                Box(
                    modifier = Modifier
                        .width(16.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(
                            if (enabled) CyanGlow.copy(alpha = 0.3f + index * 0.07f)
                            else GrayMuted.copy(alpha = 0.2f)
                        )
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Click Button
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ClickButton(
    label: String,
    enabled: Boolean,
    onButtonDown: () -> Unit,
    onButtonUp: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isPressed by remember { mutableStateOf(false) }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    !enabled -> SurfaceDark.copy(alpha = 0.4f)
                    isPressed -> CyanGlow.copy(alpha = 0.25f)
                    else -> SurfaceElevated
                }
            )
            .border(
                width = 1.dp,
                color = if (enabled && isPressed) CyanGlow else BorderSubtle,
                shape = RoundedCornerShape(14.dp)
            )
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    isPressed = true
                    onButtonDown()

                    val startTime = System.currentTimeMillis()

                    var upOrCancel = false
                    while (!upOrCancel) {
                        val event = awaitPointerEvent()
                        if (event.changes.all { !it.pressed }) {
                            upOrCancel = true
                        }
                    }

                    isPressed = false
                    onButtonUp()

                    val duration = System.currentTimeMillis() - startTime
                    if (duration < 150) {
                        onClick()
                    }
                }
            }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) (if (isPressed) CyanGlow else TextPrimary) else GrayMuted.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun StatusBadge(connectionState: ConnectionState) {
    com.blekeymouse.ui.components.StatusBadge(connectionState = connectionState)
}

@Composable
private fun AirMouseBanner(
    isConnected: Boolean,
    isAirMouseActive: Boolean,
    onToggleAirMouse: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Rounded.Explore,
                contentDescription = null,
                tint = if (isAirMouseActive) CyanGlow else GrayMuted,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Air Mouse (IMU Sensor)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isConnected) TextPrimary else TextSecondary
                )
                Text(
                    text = if (isAirMouseActive) "Move phone in air to control cursor" else "Tilt & rotation motion enabled",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            Switch(
                checked = isAirMouseActive,
                onCheckedChange = { onToggleAirMouse() },
                enabled = isConnected,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = CyanGlow,
                    checkedTrackColor = CyanGlow.copy(alpha = 0.3f),
                    uncheckedThumbColor = GrayMuted,
                    uncheckedTrackColor = SurfaceElevated
                )
            )
        }
    }
}
