package com.example.nutritrack.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFB8D8B9), onPrimary = DeepForest,
    primaryContainer = Color(0xFF294D3C), onPrimaryContainer = Color(0xFFE5F1DF),
    secondary = Color(0xFFC9D4A6), onSecondary = Color(0xFF26311A),
    secondaryContainer = Color(0xFF3D4B2C), onSecondaryContainer = Color(0xFFEAF1D5),
    tertiary = Color(0xFFF1BA98), onTertiary = Color(0xFF482613),
    tertiaryContainer = Color(0xFF623E2B), onTertiaryContainer = Color(0xFFFFEADD),
    background = Color(0xFF101C16), onBackground = Color(0xFFE6EEE1),
    surface = Color(0xFF18271E), onSurface = Color(0xFFE6EEE1),
    surfaceDim = Color(0xFF101C16), surfaceBright = Color(0xFF34453A),
    surfaceContainerLowest = Color(0xFF0C1711), surfaceContainerLow = Color(0xFF1A2B20),
    surfaceContainer = Color(0xFF1F3025), surfaceContainerHigh = Color(0xFF293A2E),
    surfaceContainerHighest = Color(0xFF34453A),
    surfaceVariant = Color(0xFF223328), onSurfaceVariant = Color(0xFFBECABB),
    outline = Color(0xFF879583), outlineVariant = Color(0xFF35473A),
    error = Color(0xFFFFB4A8), onError = Color(0xFF601814),
    errorContainer = Color(0xFF782722), onErrorContainer = Color(0xFFFFDAD3),
    inverseSurface = Color(0xFFE6EEE1), inverseOnSurface = Ink,
    inversePrimary = Forest, surfaceTint = Color(0xFFB8D8B9)
)

private val LightColorScheme = lightColorScheme(
    primary = Forest, onPrimary = Color.White,
    primaryContainer = Sage, onPrimaryContainer = DeepForest,
    secondary = Color(0xFF526A42), onSecondary = Color.White,
    secondaryContainer = Color(0xFFECF0DC), onSecondaryContainer = Ink,
    tertiary = Clay, onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFEADD), onTertiaryContainer = Color(0xFF633822),
    background = WarmCanvas, onBackground = Ink,
    surface = Color(0xFFFFFEFA), onSurface = Ink,
    surfaceDim = Color(0xFFDEE4D8), surfaceBright = Color(0xFFFFFEFA),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F4E9),
    surfaceContainer = Color(0xFFEBF0E3), surfaceContainerHigh = Color(0xFFE6EBDD),
    surfaceContainerHighest = Color(0xFFDFE6D6),
    surfaceVariant = Color(0xFFF0F3E9), onSurfaceVariant = MutedInk,
    outline = Color(0xFF7C8A7E), outlineVariant = SoftLine,
    error = Color(0xFFAD302B), onError = Color.White,
    errorContainer = Color(0xFFFFE8E4), onErrorContainer = Color(0xFF75231F),
    inverseSurface = DeepForest, inverseOnSurface = Color(0xFFF1F6ED),
    inversePrimary = Color(0xFFB8D8B9), surfaceTint = Forest
)

private val NutriShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun NutritrackTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
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
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = NutriShapes,
        content = content
    )
}
