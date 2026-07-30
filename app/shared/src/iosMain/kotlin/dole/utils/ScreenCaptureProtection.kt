package dole.utils

import platform.QuartzCore.CALayer
import platform.UIKit.UIApplication
import platform.UIKit.UITextField
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

actual object ScreenCaptureProtection {
    actual val isSupported: Boolean = true

    private var secureField: UITextField? = null
    private var secureCanvas: CALayer? = null
    private var protectedLayer: CALayer? = null
    private var hostLayer: CALayer? = null

    actual fun setBlocked(blocked: Boolean) {
        dispatch_async(dispatch_get_main_queue()) {
            if (blocked) install() else uninstall()
        }
    }

    private fun install() {
        if (secureField != null) return

        val window = keyWindow() ?: return
        val content = window.rootViewController?.view?.layer ?: return
        val parent = content.superlayer ?: return

        val field = UITextField()
        field.secureTextEntry = true
        field.userInteractionEnabled = false
        field.layoutIfNeeded()

        val canvas = field.layer.sublayers?.firstOrNull() as? CALayer ?: return
        canvas.masksToBounds = false

        parent.addSublayer(canvas)
        canvas.addSublayer(content)

        secureField = field
        secureCanvas = canvas
        protectedLayer = content
        hostLayer = parent
    }

    private fun uninstall() {
        val content = protectedLayer ?: return
        hostLayer?.addSublayer(content)
        secureCanvas?.removeFromSuperlayer()
        secureField = null
        secureCanvas = null
        protectedLayer = null
        hostLayer = null
    }

    private fun keyWindow(): UIWindow? = UIApplication.sharedApplication.connectedScenes.filterIsInstance<UIWindowScene>().firstOrNull()?.keyWindow
}
