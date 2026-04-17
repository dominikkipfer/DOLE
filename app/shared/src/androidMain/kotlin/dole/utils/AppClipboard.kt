package dole.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberAppClipboard(): AppClipboard {
    val context = LocalContext.current
    return remember(context) {
        object : AppClipboard {
            override fun copy(text: String) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Copied Content", text)
                clipboard.setPrimaryClip(clip)
            }

            override fun getText(): String? {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                return clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            }
        }
    }
}