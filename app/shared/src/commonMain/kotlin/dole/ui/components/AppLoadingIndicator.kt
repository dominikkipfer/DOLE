package dole.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
expect fun AppLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified
)