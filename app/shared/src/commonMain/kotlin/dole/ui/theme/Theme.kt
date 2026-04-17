package dole.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

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
fun DoleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}