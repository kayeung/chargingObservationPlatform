package com.chargelens.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Teal = Color(0xFF12C79A)
val TealDim = Color(0xFF0E9F7E)
val Amber = Color(0xFFFFB020)
val Sky = Color(0xFF4FB3FF)
val Coral = Color(0xFFFF6B6B)
val Violet = Color(0xFF9E8CFF)

private val DarkColors = darkColorScheme(
    primary = Teal,
    onPrimary = Color(0xFF00261C),
    secondary = Amber,
    onSecondary = Color(0xFF2A1B00),
    tertiary = Sky,
    background = Color(0xFF0A0E13),
    onBackground = Color(0xFFE9F0F7),
    surface = Color(0xFF121A23),
    onSurface = Color(0xFFE9F0F7),
    surfaceVariant = Color(0xFF17222E),
    onSurfaceVariant = Color(0xFF8FA3B5),
    outline = Color(0xFF2A3948),
    error = Coral
)

private val LightColors = lightColorScheme(
    primary = TealDim,
    onPrimary = Color.White,
    secondary = Color(0xFFC07A00),
    onSecondary = Color.White,
    tertiary = Color(0xFF1E7BC4),
    background = Color(0xFFF4F7FA),
    onBackground = Color(0xFF101A22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101A22),
    surfaceVariant = Color(0xFFEAF0F6),
    onSurfaceVariant = Color(0xFF5A6B7B),
    outline = Color(0xFFCBD7E2),
    error = Color(0xFFD64545)
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp)
)

@Composable
fun ChargeLensTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}
