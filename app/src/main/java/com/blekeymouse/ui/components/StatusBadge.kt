package com.blekeymouse.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blekeymouse.data.ConnectionState
import com.blekeymouse.ui.theme.AmberWarning
import com.blekeymouse.ui.theme.GrayMuted
import com.blekeymouse.ui.theme.GreenConnected
import com.blekeymouse.ui.theme.RedError

/**
 * Small pill-shaped badge showing the current [ConnectionState].
 *
 * - Disconnected: gray, static
 * - Connecting / Registering: amber, pulsing dot animation
 * - Connected: green, static
 * - Error: red, static
 */
@Composable
fun StatusBadge(
    connectionState: ConnectionState,
    modifier: Modifier = Modifier
) {
    val (label, color) = when (connectionState) {
        is ConnectionState.Connected    -> "Connected"    to GreenConnected
        is ConnectionState.Connecting   -> "Connecting…"  to AmberWarning
        is ConnectionState.Registering  -> "Registering…" to AmberWarning
        is ConnectionState.Error        -> "Error"         to RedError
        is ConnectionState.Disconnected -> "Disconnected"  to GrayMuted
    }

    val isAnimating = connectionState is ConnectionState.Connecting ||
            connectionState is ConnectionState.Registering

    val dotAlpha by if (isAnimating) {
        val infiniteTransition = rememberInfiniteTransition(label = "dotPulse")
        infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(600),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dotAlpha"
        )
    } else {
        androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableFloatStateOf(1f)
        }
    }

    val animatedColor by animateColorAsState(
        targetValue = color,
        animationSpec = tween(300),
        label = "badgeColor"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .alpha(dotAlpha)
                .clip(CircleShape)
                .background(animatedColor)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = animatedColor
        )
    }
}
