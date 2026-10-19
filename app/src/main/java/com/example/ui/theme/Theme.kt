package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val StatusHdColorScheme = darkColorScheme(
    primary = VenlowEmerald,
    onPrimary = Color(0xFF00210E),
    primaryContainer = VenlowEmeraldContainer,
    onPrimaryContainer = OnVenlowEmeraldContainer,
    secondary = ElectricCyan,
    onSecondary = Color(0xFF001F24),
    secondaryContainer = ElectricCyanContainer,
    onSecondaryContainer = OnElectricCyanContainer,
    tertiary = AmberWarning,
    onTertiary = Color(0xFF261900),
    background = ObsidianBackground,
    onBackground = TextPrimaryDark,
    surface = ObsidianSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerHigh = ObsidianCardElevated,
    outline = OutlineSubtle,
    error = ErrorCrimson,
    onError = Color.White
)

@Composable
fun StatusHDTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = StatusHdColorScheme,
        typography = Typography,
        content = content
    )
}
