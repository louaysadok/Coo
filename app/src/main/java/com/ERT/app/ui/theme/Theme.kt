package com.ERT.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ERT is a dark-only app: it is mostly a media grid, and a dark surface makes photographs and
// artwork read better while keeping the phone's battery draw down on an OLED panel.
private val Dark = darkColorScheme(
    primary = Color(0xFFB69CFF),
    onPrimary = Color(0xFF1B0033),
    primaryContainer = Color(0xFF3A1E77),
    onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = Color(0xFF9BE7D8),
    onSecondary = Color(0xFF00382F),
    background = Color(0xFF0B0B10),
    onBackground = Color(0xFFE8E6F0),
    surface = Color(0xFF14141C),
    onSurface = Color(0xFFE8E6F0),
    surfaceVariant = Color(0xFF1E1E28),
    onSurfaceVariant = Color(0xFFB9B7C7),
    outline = Color(0xFF3A3A48),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF4A0000)
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp)
)

@Composable
fun ErtTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Dark,
        typography = AppTypography // dark only by design; isSystemInDarkTheme is irrelevant here
    ) {
        content()
    }
}
