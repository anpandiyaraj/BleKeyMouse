package com.blekeymouse.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary          = CyanGlow,
    onPrimary        = DeepNavy,
    primaryContainer = SurfaceElevated,
    onPrimaryContainer = TextPrimary,

    secondary        = CyanDim,
    onSecondary      = DeepNavy,
    secondaryContainer = SurfaceDark,
    onSecondaryContainer = TextSecondary,

    background       = DeepNavy,
    onBackground     = TextPrimary,

    surface          = SurfaceDark,
    onSurface        = TextPrimary,
    surfaceVariant   = SurfaceElevated,
    onSurfaceVariant = TextSecondary,

    outline          = BorderSubtle,
    outlineVariant   = Color(0xFF1A2540),

    error            = RedError,
    onError          = Color.White,
)

@Composable
fun BleKeyMouseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = AppTypography,
        content     = content
    )
}
