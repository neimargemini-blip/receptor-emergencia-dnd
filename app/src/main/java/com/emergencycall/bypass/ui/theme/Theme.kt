package com.emergencycall.bypass.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = RedPrimary,
    onPrimary = TextPrimary,
    primaryContainer = RedDark,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = CardBackground,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun EmergencyCallTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
