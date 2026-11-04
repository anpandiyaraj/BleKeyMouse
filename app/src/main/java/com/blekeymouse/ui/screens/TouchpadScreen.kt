package com.blekeymouse.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blekeymouse.data.ConnectionState
import com.blekeymouse.sensor.AirMouseProvider
import com.blekeymouse.ui.theme.BorderSubtle
import com.blekeymouse.ui.theme.CyanGlow
import com.blekeymouse.ui.theme.DeepNavy
import com.blekeymouse.ui.theme.GrayMuted
import com.blekeymouse.ui.theme.SurfaceDark
import com.blekeymouse.ui.theme.SurfaceElevated
import com.blekeymouse.ui.theme.TextPrimary
import com.blekeymouse.ui.theme.TextSecondary
import com.blekeymouse.util.PointerAcceleration
import com.blekeymouse.viewmodel.MainViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

private const val BUTTON_LEFT = 0x01
private const val BUTTON_RIGHT = 0x02

/**
 * Touchpad Screen — primary mouse & remote control surface with support for:
 *  • 1-finger drag → cursor movement
 *  • 1-finger tap → left click
 *  • 2-finger tap → right click
 *  • 2-finger vertical drag → vertical scroll (natural direction)
 *  • 2-finger horizontal drag → horizontal scroll (AC Pan)
 *  • 2-finger quick flick → touch swipe gesture (for YouTube Shorts / Reels / Launcher pages on Android hosts)
 *  • Scroll strips (vertical + horizontal)
 *  • Quick swipe & navigation bar (Next/Prev Short, Launcher page left/right, Back, Home, Recents, Play/Pause)
 *  • Dedicated left & right click buttons supporting click & drag hold without double-firing
 */
@Composable
fun TouchpadScreen(viewModel: MainViewModel) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val isAirMouseActive by viewModel.isAirMouseActive.collectAsStateWithLifecycle()
    val airMouseSensitivity by viewModel.airMouseSensitivity.collectAsStateWithLifecycle()

    val isConnected = connectionState is ConnectionState.Connected
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepNavy)
            .padding(horizontal = 16.dp, vertical = 10.dp),
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

        Spacer(modifier = Modifier.height(8.dp))

        // ── Air Mouse Control Banner + Sensitivity Slider ────────────────
        AirMouseBanner(
            isConnected = isConnected,
            isAirMouseActive = isAirMouseActive,
            sensitivity = airMouseSensitivity,
            onToggleAirMouse = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.toggleAirMouse()
            },
            onSensitivityChange = { viewModel.setAirMouseSensitivity(it) }
        )

        Spacer(modifier = Modifier.height(8.dp))

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
                onVerticalScroll = { delta -> viewModel.sendScroll(delta) },
                onHorizontalScroll = { delta -> viewModel.sendHorizontalScroll(delta) },
                onSwipeUp = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.sendSwipeUp()
                },
                onSwipeDown = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.sendSwipeDown()
                },
                onSwipeLeft = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.sendSwipeLeft()
                },
                onSwipeRight = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.sendSwipeRight()
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Scroll strip (vertical right side)
            ScrollStrip(
                enabled = isConnected,
                onScroll = { delta -> viewModel.sendScroll(delta) },
                modifier = Modifier
                    .width(38.dp)
                    .fillMaxHeight()
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Horizontal Scroll Strip ──────────────────────────────────────
        HorizontalScrollStrip(
            enabled = isConnected,
            onScroll = { delta -> viewModel.sendHorizontalScroll(delta) },
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ── Phone / Shorts / Launcher Quick Navigation Bar ───────────────
        QuickNavRow(
            enabled = isConnected,
            onSwipeLeft = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.sendSwipeLeft()
            },
            onSwipeUp = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.sendSwipeUp()
            },
            onSwipeDown = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.sendSwipeDown()
            },
            onSwipeRight = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.sendSwipeRight()
            },
            onBack = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.sendBackKey()
            },
            onHome = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.sendHomeKey()
            },
            onRecents = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.sendRecentsKey()
            },
            onPlayPause = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.sendPlayPause()
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ── Click & ESC Buttons ──────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HoldClickButton(
                label = "Left Click",
                enabled = isConnected,
                onButtonDown = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.setMouseButtonState(BUTTON_LEFT, isDown = true)
                },
                onButtonUp = {
                    viewModel.setMouseButtonState(BUTTON_LEFT, isDown = false)
                },
                modifier = Modifier.weight(1f)
            )
            HoldClickButton(
                label = "ESC",
                enabled = isConnected,
                onButtonDown = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.sendEscapeKey()
                },
                onButtonUp = {},
                modifier = Modifier.weight(0.65f)
            )
            HoldClickButton(
                label = "Right Click",
                enabled = isConnected,
                onButtonDown = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.setMouseButtonState(BUTTON_RIGHT, isDown = true)
                },
                onButtonUp = {
                    viewModel.setMouseButtonState(BUTTON_RIGHT, isDown = false)
                },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(78.dp)) // bottom nav space
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Touchpad Surface — unified 1-finger & 2-finger gesture handler
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TouchpadSurface(
    enabled: Boolean,
    onDelta: (dx: Int, dy: Int) -> Unit,
    onLeftClick: () -> Unit,
    onRightClick: () -> Unit,
    onVerticalScroll: (Int) -> Unit,
    onHorizontalScroll: (Int) -> Unit,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    modifier: Modifier = Modifier
) {
    var touchIndicator by remember { mutableStateOf<Offset?>(null) }
    val density = LocalDensity.current

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
                val tapSlopPx = viewConfiguration.touchSlop
                val scrollStepPx = 14f

                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    firstDown.consume()

                    val startTime = System.currentTimeMillis()
                    var maxPointers = 1
                    var totalMove1 = 0f
                    var totalMove2X = 0f
                    var totalMove2Y = 0f
                    var accumMoveX = 0f
                    var accumMoveY = 0f
                    var accumScrollX = 0f
                    var accumScrollY = 0f

                    touchIndicator = firstDown.position

                    while (true) {
                        val event = awaitPointerEvent()
                        val active = event.changes.filter { it.pressed }
                        if (active.isEmpty()) break

                        if (active.size > maxPointers) {
                            maxPointers = active.size
                        }

                        if (maxPointers == 1) {
                            val change = active.first()
                            val delta = change.position - change.previousPosition
                            change.consume()

                            totalMove1 += delta.getDistance()
                            touchIndicator = change.position

                            if (totalMove1 > tapSlopPx * 0.4f) {
                                val (scaledX, scaledY) = PointerAcceleration.scaleDelta(delta.x, delta.y)
                                accumMoveX += scaledX
                                accumMoveY += scaledY
                                val sendX = accumMoveX.toInt()
                                val sendY = accumMoveY.toInt()
                                if (sendX != 0 || sendY != 0) {
                                    accumMoveX -= sendX
                                    accumMoveY -= sendY
                                    onDelta(sendX, sendY)
                                }
                            }
                        } else if (active.size >= 2) {
                            // 2-finger gesture: average delta across first two fingers
                            val c0 = active[0]
                            val c1 = active[1]
                            val d0 = c0.position - c0.previousPosition
                            val d1 = c1.position - c1.previousPosition
                            val avgDx = (d0.x + d1.x) * 0.5f
                            val avgDy = (d0.y + d1.y) * 0.5f
                            event.changes.forEach { it.consume() }

                            totalMove2X += avgDx
                            totalMove2Y += avgDy
                            touchIndicator = Offset(
                                (c0.position.x + c1.position.x) * 0.5f,
                                (c0.position.y + c1.position.y) * 0.5f
                            )

                            accumScrollX += avgDx
                            accumScrollY += avgDy

                            if (abs(accumScrollY) >= abs(accumScrollX)) {
                                if (abs(accumScrollY) >= scrollStepPx) {
                                    val steps = (accumScrollY / scrollStepPx).toInt()
                                    accumScrollY -= steps * scrollStepPx
                                    // Dragging fingers UP (negative avgDy) scrolls down; invert for wheel
                                    onVerticalScroll(-steps)
                                }
                            } else {
                                if (abs(accumScrollX) >= scrollStepPx) {
                                    val steps = (accumScrollX / scrollStepPx).toInt()
                                    accumScrollX -= steps * scrollStepPx
                                    onHorizontalScroll(-steps)
                                }
                            }
                        }
                    }

                    touchIndicator = null
                    val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                    val totalMove2Dist = kotlin.math.sqrt(totalMove2X * totalMove2X + totalMove2Y * totalMove2Y)

                    when {
                        // 1-finger tap → Left click
                        maxPointers == 1 && totalMove1 < tapSlopPx && elapsed < 300L -> {
                            onLeftClick()
                        }
                        // 2-finger tap → Right click (or Back on Android)
                        maxPointers >= 2 && totalMove2Dist < tapSlopPx * 1.4f && elapsed < 320L -> {
                            onRightClick()
                        }
                        // Fast 2-finger flick → touch swipe gesture (snaps Shorts / Launcher pages)
                        maxPointers >= 2 && elapsed < 240L && totalMove2Dist > tapSlopPx * 4f -> {
                            if (abs(totalMove2Y) >= abs(totalMove2X)) {
                                if (totalMove2Y < 0) onSwipeUp() else onSwipeDown()
                            } else {
                                if (totalMove2X < 0) onSwipeLeft() else onSwipeRight()
                            }
                        }
                    }
                }
            }
    ) {
        // Touch ripple indicator
        touchIndicator?.let { pos ->
            val xDp = with(density) { pos.x.toDp() - 14.dp }
            val yDp = with(density) { pos.y.toDp() - 14.dp }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .offset(x = xDp, y = yDp)
                    .clip(CircleShape)
                    .background(CyanGlow.copy(alpha = 0.25f))
            )
        }

        // Center label when idle
        if (touchIndicator == null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.TouchApp,
                    contentDescription = null,
                    tint = if (enabled) CyanGlow.copy(alpha = 0.3f) else GrayMuted.copy(alpha = 0.3f),
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (enabled) "1F: Move / Tap • 2F: Right-Click / Scroll / Flick" else "Not connected",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) CyanGlow.copy(alpha = 0.45f) else GrayMuted.copy(alpha = 0.4f)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Quick Swipe & Phone Navigation Bar (Shorts / Reels / Launcher / System Nav)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun QuickNavRow(
    enabled: Boolean,
    onSwipeLeft: () -> Unit,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
    onSwipeRight: () -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onRecents: () -> Unit,
    onPlayPause: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        QuickActionChip(
            icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
            label = "Page",
            enabled = enabled,
            onClick = onSwipeRight,
            modifier = Modifier.weight(1f)
        )
        QuickActionChip(
            icon = Icons.Rounded.KeyboardArrowUp,
            label = "Next",
            enabled = enabled,
            onClick = onSwipeUp,
            modifier = Modifier.weight(1f)
        )
        QuickActionChip(
            icon = Icons.Rounded.KeyboardArrowDown,
            label = "Prev",
            enabled = enabled,
            onClick = onSwipeDown,
            modifier = Modifier.weight(1f)
        )
        QuickActionChip(
            icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            label = "Page",
            enabled = enabled,
            onClick = onSwipeLeft,
            modifier = Modifier.weight(1f)
        )
        QuickActionChip(
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            label = "Back",
            enabled = enabled,
            onClick = onBack,
            modifier = Modifier.weight(1f)
        )
        QuickActionChip(
            icon = Icons.Rounded.Home,
            label = "Home",
            enabled = enabled,
            onClick = onHome,
            modifier = Modifier.weight(1f)
        )
        QuickActionChip(
            icon = Icons.Rounded.Apps,
            label = "Apps",
            enabled = enabled,
            onClick = onRecents,
            modifier = Modifier.weight(1f)
        )
        QuickActionChip(
            icon = Icons.Rounded.PlayArrow,
            label = "Play",
            enabled = enabled,
            onClick = onPlayPause,
            modifier = Modifier.weight(0.9f)
        )
    }
}

@Composable
private fun QuickActionChip(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) SurfaceElevated else SurfaceDark.copy(alpha = 0.4f))
            .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (enabled) CyanGlow else GrayMuted.copy(alpha = 0.4f),
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
            color = if (enabled) TextSecondary else GrayMuted.copy(alpha = 0.4f),
            maxLines = 1
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Scroll Strip — Mouse Scroll Wheel
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ScrollStrip(
    enabled: Boolean,
    onScroll: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var accumScrollY by remember { mutableStateOf(0f) }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceDark)
            .border(
                1.dp,
                if (enabled) CyanGlow.copy(alpha = 0.3f) else BorderSubtle,
                RoundedCornerShape(20.dp)
            )
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { accumScrollY = 0f },
                    onDragEnd = { accumScrollY = 0f },
                    onDragCancel = { accumScrollY = 0f },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        accumScrollY += dragAmount.y
                        // Responsive scroll step threshold (10px per notch)
                        val scrollThreshold = 10f
                        if (abs(accumScrollY) >= scrollThreshold) {
                            val steps = (accumScrollY / scrollThreshold).toInt()
                            accumScrollY -= steps * scrollThreshold
                            // Invert y: dragging finger UP (negative dragAmount.y) scrolls UP (positive wheel)
                            onScroll(-steps)
                        }
                    }
                )
            }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 12.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.UnfoldMore,
                contentDescription = "Scroll Wheel",
                tint = if (enabled) CyanGlow else GrayMuted.copy(alpha = 0.4f),
                modifier = Modifier.size(18.dp)
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                repeat(4) { index ->
                    Box(
                        modifier = Modifier
                            .width(14.dp)
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(
                                if (enabled) CyanGlow.copy(alpha = 0.3f + index * 0.15f)
                                else GrayMuted.copy(alpha = 0.2f)
                            )
                    )
                }
            }

            Text(
                text = "SCROLL",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 7.sp),
                color = if (enabled) CyanGlow.copy(alpha = 0.7f) else GrayMuted.copy(alpha = 0.4f),
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Horizontal Scroll Strip — Horizontal Pan Wheel
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HorizontalScrollStrip(
    enabled: Boolean,
    onScroll: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var accumScrollX by remember { mutableStateOf(0f) }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceDark)
            .border(
                1.dp,
                if (enabled) CyanGlow.copy(alpha = 0.3f) else BorderSubtle,
                RoundedCornerShape(14.dp)
            )
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { accumScrollX = 0f },
                    onDragEnd = { accumScrollX = 0f },
                    onDragCancel = { accumScrollX = 0f },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        accumScrollX += dragAmount.x
                        val scrollThreshold = 12f
                        if (abs(accumScrollX) >= scrollThreshold) {
                            val steps = (accumScrollX / scrollThreshold).toInt()
                            accumScrollX -= steps * scrollThreshold
                            onScroll(steps)
                        }
                    }
                )
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.UnfoldMore,
                contentDescription = "Horizontal Scroll",
                tint = if (enabled) CyanGlow else GrayMuted.copy(alpha = 0.4f),
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer(rotationZ = 90f)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                repeat(6) { index ->
                    Box(
                        modifier = Modifier
                            .height(14.dp)
                            .width(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(
                                if (enabled) CyanGlow.copy(alpha = 0.3f + index * 0.10f)
                                else GrayMuted.copy(alpha = 0.2f)
                            )
                    )
                }
            }

            Text(
                text = "H-SCROLL",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 7.sp),
                color = if (enabled) CyanGlow.copy(alpha = 0.7f) else GrayMuted.copy(alpha = 0.4f),
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Hold / Click Button — sends press on down and release on up (no double-click)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HoldClickButton(
    label: String,
    enabled: Boolean,
    onButtonDown: () -> Unit,
    onButtonUp: () -> Unit,
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

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.all { !it.pressed }) break
                        }
                    } finally {
                        isPressed = false
                        onButtonUp()
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
    sensitivity: Float,
    onToggleAirMouse: () -> Unit,
    onSensitivityChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp)
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
                    text = if (isAirMouseActive) "Point & tilt phone • Speed ${sensitivity.roundToInt()}" else "Tilt & wrist motion cursor control",
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

        AnimatedVisibility(visible = isAirMouseActive && isConnected) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
            ) {
                Text(
                    text = "Speed",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Slider(
                    value = sensitivity,
                    onValueChange = onSensitivityChange,
                    valueRange = AirMouseProvider.MIN_SENSITIVITY..AirMouseProvider.MAX_SENSITIVITY,
                    colors = SliderDefaults.colors(
                        thumbColor = CyanGlow,
                        activeTrackColor = CyanGlow,
                        inactiveTrackColor = SurfaceElevated
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(24.dp)
                )
            }
        }
    }
}
