package dole.utils

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary

actual object ScreenCaptureProtection {
    private const val WDA_NONE = 0x0
    private const val WDA_EXCLUDEFROMCAPTURE = 0x11

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private var window: java.awt.Window? = null
    private var blocked = false

    actual val isSupported: Boolean = isWindows

    fun bind(window: java.awt.Window) {
        this.window = window
        apply()
    }

    actual fun setBlocked(blocked: Boolean) {
        this.blocked = blocked
        apply()
    }

    private fun apply() {
        if (!isWindows) return
        val target = window ?: return

        try {
            val hwnd = Native.getComponentPointer(target) ?: return
            User32.INSTANCE.SetWindowDisplayAffinity(
                hwnd,
                if (blocked) WDA_EXCLUDEFROMCAPTURE else WDA_NONE
            )
        } catch (_: Throwable) { }
    }

    @Suppress("FunctionName")
    private interface User32 : StdCallLibrary {
        fun SetWindowDisplayAffinity(hWnd: Pointer, dwAffinity: Int): Boolean

        companion object {
            val INSTANCE: User32 by lazy { Native.load("user32", User32::class.java) }
        }
    }
}
