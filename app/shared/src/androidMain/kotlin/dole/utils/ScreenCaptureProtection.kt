package dole.utils

import android.app.Activity
import android.view.WindowManager
import java.lang.ref.WeakReference

actual object ScreenCaptureProtection {
    private var activityRef: WeakReference<Activity>? = null
    private var blocked = false

    actual val isSupported: Boolean = true

    fun bind(activity: Activity) {
        activityRef = WeakReference(activity)
        apply()
    }

    actual fun setBlocked(blocked: Boolean) {
        this.blocked = blocked
        apply()
    }

    private fun apply() {
        val activity = activityRef?.get() ?: return
        if (blocked) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
