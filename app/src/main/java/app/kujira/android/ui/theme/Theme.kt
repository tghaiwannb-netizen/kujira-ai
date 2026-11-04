package app.kujira.android.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkScheme = darkColorScheme(
    primary = Ember400,
    onPrimary = Color(0xFF2A1109),
    primaryContainer = Ember900,
    onPrimaryContainer = Ember300,
    secondary = Ink300,
    onSecondary = Ink950,
    secondaryContainer = Ink800,
    onSecondaryContainer = Ink100,
    tertiary = Sage500,
    onTertiary = Color(0xFF0E2416),
    background = Ink950,
    onBackground = Ink100,
    surface = Ink950,
    onSurface = Ink100,
    surfaceVariant = Ink850,
    onSurfaceVariant = Ink300,
    surfaceContainerLowest = Color(0xFF0A0A0C),
    surfaceContainerLow = Ink900,
    surfaceContainer = Ink850,
    surfaceContainerHigh = Ink800,
    surfaceContainerHighest = Ink700,
    outline = Ink600,
    outlineVariant = Ink700,
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    inverseSurface = Ink100,
    inverseOnSurface = Ink950,
    scrim = Color.Black
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFFB4532F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF6D8CC),
    onPrimaryContainer = Color(0xFF3D1B10),
    secondary = Clay600,
    onSecondary = Color.White,
    secondaryContainer = Paper200,
    onSecondaryContainer = Clay900,
    tertiary = Color(0xFF4C6B57),
    onTertiary = Color.White,
    background = Paper100,
    onBackground = Clay900,
    surface = Paper100,
    onSurface = Clay900,
    surfaceVariant = Paper200,
    onSurfaceVariant = Clay600,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Paper50,
    surfaceContainer = Paper100,
    surfaceContainerHigh = Paper200,
    surfaceContainerHighest = Paper300,
    outline = Color(0xFFB5A99A),
    outlineVariant = Paper300,
    error = Color(0xFFB3261E),
    onError = Color.White,
    inverseSurface = Clay900,
    inverseOnSurface = Paper100,
    scrim = Color.Black
)

@Composable
fun KujiraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkScheme else LightScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = KujiraTypography,
        content = content
    )
}
