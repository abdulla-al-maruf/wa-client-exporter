package com.jbd.waexport.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = MintDeep, onPrimary = Color.White,
    primaryContainer = Color(0xFFC9F0DA), onPrimaryContainer = Color(0xFF0C3D27),
    secondary = PeachDeep, onSecondary = Color.White,
    secondaryContainer = Color(0xFFFBE0C8), onSecondaryContainer = Color(0xFF5A3413),
    tertiary = Color(0xFF6E79D6), onTertiary = Color.White,
    error = Color(0xFFC24B43), onError = Color.White,
    background = Cream, onBackground = Ink,
    surface = Color(0xFFFFFDF8), onSurface = Ink,
    surfaceVariant = Color(0xFFECE7DA), onSurfaceVariant = Color(0xFF4A4A45),
    outline = Color(0xFFC9C3B4)
)

private val DarkColors = darkColorScheme(
    primary = Mint, onPrimary = Color(0xFF06321F),
    primaryContainer = Color(0xFF1E4634), onPrimaryContainer = Color(0xFFB9F0D2),
    secondary = Peach, onSecondary = Color(0xFF4A2A0E),
    secondaryContainer = Color(0xFF5A3413), onSecondaryContainer = Color(0xFFFBE0C8),
    tertiary = Periwinkle, onTertiary = Color(0xFF1B2150),
    error = Coral, onError = Color(0xFF3A0D0A),
    background = Color(0xFF17181C), onBackground = Color(0xFFEDEDE6),
    surface = Color(0xFF1F2125), onSurface = Color(0xFFEDEDE6),
    surfaceVariant = Color(0xFF2C2E33), onSurfaceVariant = Color(0xFFC4C2BA),
    outline = Color(0xFF45474C)
)

@Composable
fun WaExporterTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (dark) DarkColors else LightColors
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
