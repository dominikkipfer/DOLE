package dole.ui.components

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
actual fun AppLoadingIndicator(modifier: Modifier, color: Color) {
    CircularProgressIndicator(color = color, modifier = modifier, strokeWidth = 4.dp)
}