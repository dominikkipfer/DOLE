package dole.utils

import platform.QuartzCore.CALayer
import platform.UIKit.UIApplication
import platform.UIKit.UITextField
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene

actual object ScreenCaptureProtection {
    actual val isSupported: Boolean = true

    private var secureField: UITextField? = null

    actual fun setBlocked(blocked: Boolean) {
        val field = secureField ?: (if (blocked) installSecureContainer() else null) ?: return
        field.secureTextEntry = blocked
    }

    private fun installSecureContainer(): UITextField? {
        val window = keyWindow() ?: return null

        val field = UITextField()
        field.secureTextEntry = true
        field.userInteractionEnabled = false
        window.addSubview(field)

        val canvas = field.layer.sublayers?.lastOrNull() as? CALayer
        if (canvas == null) {
            field.removeFromSuperview()
            println("ScreenCaptureProtection: secure canvas layer unavailable")
            return null
        }

        window.layer.superlayer?.addSublayer(field.layer)
        canvas.addSublayer(window.layer)

        secureField = field
        return field
    }

    private fun keyWindow(): UIWindow? =
        UIApplication.sharedApplication.connectedScenes.filterIsInstance<UIWindowScene>().firstOrNull()?.keyWindow
}
