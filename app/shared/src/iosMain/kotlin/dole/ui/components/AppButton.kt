package dole.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIButton
import platform.UIKit.UIButtonTypeCustom
import platform.UIKit.UIButtonTypeSystem
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UIColor
import platform.UIKit.UIAction
import platform.UIKit.UIControlEventTouchUpInside

private fun Color.toUIColor(): UIColor {
    return UIColor(
        red = this.red.toDouble(),
        green = this.green.toDouble(),
        blue = this.blue.toDouble(),
        alpha = this.alpha.toDouble()
    )
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun AppButton(text: String, onClick: () -> Unit, modifier: Modifier, backgroundColor: Color, textColor: Color) {
    UIKitView(
        factory = {
            val button = UIButton.buttonWithType(UIButtonTypeCustom)
            button.layer.cornerRadius = 8.0
            button.clipsToBounds = true

            button.backgroundColor = backgroundColor.toUIColor()
            button.setTitleColor(textColor.toUIColor(), UIControlStateNormal)

            val action = UIAction.actionWithHandler { _ -> onClick() }
            button.addAction(action, forControlEvents = UIControlEventTouchUpInside)

            button
        },
        update = { button ->
            button.setTitle(text, UIControlStateNormal)
            button.backgroundColor = backgroundColor.toUIColor()
            button.setTitleColor(textColor.toUIColor(), UIControlStateNormal)
        },
        modifier = modifier
    )
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun AppTextButton(text: String, onClick: () -> Unit, modifier: Modifier, textColor: Color) {
    UIKitView(
        factory = {
            val button = UIButton.buttonWithType(UIButtonTypeSystem)
            button.setTitleColor(textColor.toUIColor(), UIControlStateNormal)

            val action = UIAction.actionWithHandler { _ -> onClick() }
            button.addAction(action, forControlEvents = UIControlEventTouchUpInside)

            button
        },
        update = { button ->
            button.setTitle(text, UIControlStateNormal)
            button.setTitleColor(textColor.toUIColor(), UIControlStateNormal)
        },
        modifier = modifier
    )
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun AppOutlinedButton(text: String, onClick: () -> Unit, modifier: Modifier, textColor: Color) {
    UIKitView(
        factory = {
            val button = UIButton.buttonWithType(UIButtonTypeSystem)
            button.layer.borderWidth = 1.0
            button.layer.cornerRadius = 12.0

            @Suppress("MISSING_DEPENDENCY_CLASS")
            button.layer.borderColor = textColor.toUIColor().CGColor
            button.setTitleColor(textColor.toUIColor(), UIControlStateNormal)

            val action = UIAction.actionWithHandler { _ -> onClick() }
            button.addAction(action, forControlEvents = UIControlEventTouchUpInside)

            button
        },
        update = { button ->
            button.setTitle(text, UIControlStateNormal)

            @Suppress("MISSING_DEPENDENCY_CLASS")
            button.layer.borderColor = textColor.toUIColor().CGColor
            button.setTitleColor(textColor.toUIColor(), UIControlStateNormal)
        },
        modifier = modifier
    )
}