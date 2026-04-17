package dole.utils

import androidx.compose.runtime.Composable

interface AppClipboard {
    fun copy(text: String)
    fun getText(): String?
}

@Composable
expect fun rememberAppClipboard(): AppClipboard