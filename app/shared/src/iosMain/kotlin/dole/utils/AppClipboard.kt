package dole.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIPasteboard

@Composable
actual fun rememberAppClipboard(): AppClipboard {
    return remember {
        object : AppClipboard {
            override fun copy(text: String) {
                UIPasteboard.generalPasteboard.string = text
            }

            override fun getText(): String? {
                return UIPasteboard.generalPasteboard.string
            }
        }
    }
}