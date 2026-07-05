package dole.utils

expect object ScreenCaptureProtection {
    val isSupported: Boolean

    fun setBlocked(blocked: Boolean)
}
