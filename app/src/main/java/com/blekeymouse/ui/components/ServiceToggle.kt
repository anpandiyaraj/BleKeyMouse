package com.blekeymouse.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blekeymouse.ui.theme.BorderSubtle
import com.blekeymouse.ui.theme.CyanGlow
import com.blekeymouse.ui.theme.DeepNavy
import com.blekeymouse.ui.theme.GrayMuted
import com.blekeymouse.ui.theme.SurfaceElevated
import com.blekeymouse.ui.theme.TextSecondary

/**
 * Service toggle row — shows a labeled switch to turn the foreground HID service on/off.
 *
 * The container changes color when the service is running to provide clear visual feedback.
 */
@Composable
fun ServiceToggle(
    isRunning: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor by animateColorAsState(
        targetValue = if (isRunning) CyanGlow.copy(alpha = 0.10f) else SurfaceElevated,
        animationSpec = tween(400),
        label = "serviceToggleBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isRunning) CyanGlow.copy(alpha = 0.4f) else BorderSubtle,
        animationSpec = tween(400),
        label = "serviceToggleBorder"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(containerColor)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.PowerSettingsNew,
                contentDescription = "Service power",
                tint = if (isRunning) CyanGlow else GrayMuted,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = if (isRunning) "HID Service Running" else "HID Service Stopped",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isRunning) CyanGlow else TextSecondary
            )
        }

        Switch(
            checked = isRunning,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor      = DeepNavy,
                checkedTrackColor      = CyanGlow,
                uncheckedThumbColor    = GrayMuted,
                uncheckedTrackColor    = Color(0xFF1C2537),
                uncheckedBorderColor   = BorderSubtle
            )
        )
    }
}
