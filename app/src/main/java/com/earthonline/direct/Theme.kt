package com.earthonline.direct

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF0B1220)
private val Cyan = Color(0xFF35D6E8)
private val Amber = Color(0xFFFFB020)
private val Paper = Color(0xFFF6F8FB)
private val Deep = Color(0xFF12324A)

private val DarkScheme = darkColorScheme(
    primary = Cyan,
    onPrimary = Ink,
    secondary = Amber,
    onSecondary = Ink,
    background = Ink,
    onBackground = Color(0xFFE8F1F6),
    surface = Color(0xFF111C2C),
    onSurface = Color(0xFFE8F1F6),
    surfaceVariant = Color(0xFF1B2A3E),
    onSurfaceVariant = Color(0xFFAFC3D4),
    outline = Color(0xFF33475F)
)

private val LightScheme = lightColorScheme(
    primary = Deep,
    onPrimary = Color.White,
    secondary = Color(0xFFB06E00),
    onSecondary = Color.White,
    background = Paper,
    onBackground = Color(0xFF10202E),
    surface = Color.White,
    onSurface = Color(0xFF10202E),
    surfaceVariant = Color(0xFFE7EEF5),
    onSurfaceVariant = Color(0xFF44586B),
    outline = Color(0xFFB9C8D6)
)

@Composable
fun EarthOnlineTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        content = content
    )
}