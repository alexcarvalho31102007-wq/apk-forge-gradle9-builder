package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryBlueDark,
    onPrimary = Color(0xFF002D6C),
    primaryContainer = Color(0xFF00439D),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFF90CDF4),
    onSecondary = Color(0xFF003355),
    secondaryContainer = Color(0xFF004B75),
    onSecondaryContainer = Color(0xFFCBE6FF),
    tertiary = SuccessGreenLight,
    onTertiary = Color(0xFF003915),
    background = SlateBackgroundDark,
    onBackground = Color(0xFFE2E8F0),
    surface = SlateSurfaceDark,
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = SlateSurfaceVariantDark,
    onSurfaceVariant = Color(0xFFCBD5E1),
    error = ErrorRedLight,
    onError = Color(0xFF680010)
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF001944),
    secondary = Color(0xFF0284C7),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF001F33),
    tertiary = SuccessGreen,
    onTertiary = Color.White,
    background = SlateBackgroundLight,
    onBackground = Color(0xFF0F172A),
    surface = SlateSurfaceLight,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = SlateSurfaceVariantLight,
    onSurfaceVariant = Color(0xFF475569),
    error = ErrorRed,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
