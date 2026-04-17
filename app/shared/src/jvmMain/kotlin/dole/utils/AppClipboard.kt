package dole.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Composable
actual fun rememberAppClipboard(): AppClipboard {
    return remember {
        object : AppClipboard {
            override fun copy(text: String) {
                val selection = StringSelection(text)
                Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
            }

            override fun getText(): String? {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                return try {
                    clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}