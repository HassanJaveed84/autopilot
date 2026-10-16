package com.lifeadmin.autopilot.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Teal = Color(0xFF0F6E73)
private val TealDark = Color(0xFF7FD3D6)
private val Amber = Color(0xFFB7791F)

private val LightColors: ColorScheme = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB4EEF0),
    onPrimaryContainer = Color(0xFF002022),
    secondary = Color(0xFF4A6364),
    secondaryContainer = Color(0xFFCCE8E9),
    tertiary = Amber,
    tertiaryContainer = Color(0xFFFFDDB0),
    background = Color(0xFFF8FAF9),
    surface = Color(0xFFF8FAF9),
    error = Color(0xFFBA1A1A),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = TealDark,
    onPrimary = Color(0xFF003739),
    primaryContainer = Color(0xFF004F53),
    onPrimaryContainer = Color(0xFFB4EEF0),
    secondary = Color(0xFFB0CCCD),
    secondaryContainer = Color(0xFF324B4C),
    tertiary = Color(0xFFFFB952),
    tertiaryContainer = Color(0xFF633F00),
    background = Color(0xFF101414),
    surface = Color(0xFF101414),
    error = Color(0xFFFFB4AB),
)

private val AppTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
        labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    )
}

@Composable
fun LifeAdminTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, typography = AppTypography, content = content)
}
