package dole.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIActivityIndicatorView
import platform.UIKit.UIActivityIndicatorViewStyleLarge

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun AppLoadingIndicator(modifier: Modifier, color: Color) {
    UIKitView(
        factory = {
            val indicator = UIActivityIndicatorView(UIActivityIndicatorViewStyleLarge)
            indicator.startAnimating()
            indicator
        },
        update = {},
        modifier = modifier
    )
}