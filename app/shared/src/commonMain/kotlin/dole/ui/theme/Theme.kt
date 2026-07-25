package dole.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

enum class ThemeMode { SYSTEM, LIGHT, DARK }

val LocalIsDarkTheme = staticCompositionLocalOf { false }

private val LightColors = lightColorScheme(
    primary = DoleBlack,
    onPrimary = DoleWhite,
    background = DoleGray,
    surface = DoleWhite,
    error = DoleRed
)

private val DarkColors = darkColorScheme(
    primary = DoleWhite,
    onPrimary = DoleBlack,
    background = DoleBlack,
    surface = DoleDark,
    error = DoleRed
)

@Composable
fun DoleTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors) {
        CompositionLocalProvider(LocalIsDarkTheme provides darkTheme, content = content)
    }
}
