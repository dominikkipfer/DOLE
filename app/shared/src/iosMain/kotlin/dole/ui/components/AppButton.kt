package dole.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIAction
import platform.UIKit.UIButton
import platform.UIKit.UIButtonConfiguration
import platform.UIKit.UIButtonTypeSystem
import platform.UIKit.UIColor
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIControlEventValueChanged
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UISwitch

private val supportsLiquidGlass: Boolean by lazy {
    NSProcessInfo.processInfo.operatingSystemVersion.useContents { majorVersion >= 26 }
}

private fun Color.toUIColor() = UIColor(
    red = red.toDouble(),
    green = green.toDouble(),
    blue = blue.toDouble(),
    alpha = alpha.toDouble()
)

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    backgroundColor: Color,
    textColor: Color
) {
    val currentOnClick by rememberUpdatedState(onClick)
    UIKitView(
        factory = {
            val button = UIButton.buttonWithType(UIButtonTypeSystem)
            val action = UIAction.actionWithHandler { _ -> currentOnClick() }
            button.addAction(action, forControlEvents = UIControlEventTouchUpInside)
            button
        },
        update = { button ->
            val config = if (supportsLiquidGlass) {
                UIButtonConfiguration.prominentGlassButtonConfiguration()
            } else {
                UIButtonConfiguration.tintedButtonConfiguration().apply {
                    baseBackgroundColor = backgroundColor.toUIColor()
                }
            }
            config.title = text
            config.baseForegroundColor = textColor.toUIColor()
            button.tintColor = backgroundColor.toUIColor()
            button.configuration = config
        },
        modifier = modifier
    )
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun AppTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    textColor: Color
) {
    val currentOnClick by rememberUpdatedState(onClick)
    UIKitView(
        factory = {
            val button = UIButton.buttonWithType(UIButtonTypeSystem)
            val action = UIAction.actionWithHandler { _ -> currentOnClick() }
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
actual fun AppOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    textColor: Color
) {
    val currentOnClick by rememberUpdatedState(onClick)
    UIKitView(
        factory = {
            val button = UIButton.buttonWithType(UIButtonTypeSystem)
            val action = UIAction.actionWithHandler { _ -> currentOnClick() }
            button.addAction(action, forControlEvents = UIControlEventTouchUpInside)
            button
        },
        update = { button ->
            if (supportsLiquidGlass) {
                val config = UIButtonConfiguration.glassButtonConfiguration()
                config.title = text
                config.baseForegroundColor = textColor.toUIColor()
                button.configuration = config
            } else {
                button.layer.borderWidth = 1.0
                button.layer.cornerRadius = 12.0
                button.layer.borderColor = textColor.toUIColor().CGColor
                button.setTitle(text, UIControlStateNormal)
                button.setTitleColor(textColor.toUIColor(), UIControlStateNormal)
            }
        },
        modifier = modifier
    )
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun AppSwitchButton(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier
) {
    val currentOnCheckedChange by rememberUpdatedState(onCheckedChange)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        UIKitView(
            factory = {
                val switch = UISwitch()
                val action = UIAction.actionWithHandler { _ -> currentOnCheckedChange(switch.on) }
                switch.addAction(action, forControlEvents = UIControlEventValueChanged)
                switch
            },
            update = { switch ->
                if (switch.on != checked) switch.setOn(checked, animated = false)
            },
            modifier = Modifier.width(51.dp).height(31.dp)
        )
    }
}
