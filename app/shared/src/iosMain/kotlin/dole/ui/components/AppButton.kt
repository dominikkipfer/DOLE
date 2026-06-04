package dole.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIAction
import platform.UIKit.UIButton
import platform.UIKit.UIButtonConfiguration
import platform.UIKit.UIButtonTypeSystem
import platform.UIKit.UIColor
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIControlStateNormal

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    backgroundColor: Color,
    textColor: Color
) {
    UIKitView(
        factory = {
            val button = UIButton()
            val config = UIButtonConfiguration.tintedButtonConfiguration()

            config.baseBackgroundColor = UIColor(
                red = backgroundColor.red.toDouble(),
                green = backgroundColor.green.toDouble(),
                blue = backgroundColor.blue.toDouble(),
                alpha = backgroundColor.alpha.toDouble()
            )
            config.baseForegroundColor = UIColor(
                red = textColor.red.toDouble(),
                green = textColor.green.toDouble(),
                blue = textColor.blue.toDouble(),
                alpha = textColor.alpha.toDouble()
            )
            button.configuration = config

            val action = UIAction.actionWithHandler { _ -> onClick() }
            button.addAction(action, forControlEvents = UIControlEventTouchUpInside)

            button
        },
        update = { button ->
            val config = button.configuration ?: UIButtonConfiguration.tintedButtonConfiguration()
            config.title = text
            config.baseBackgroundColor = UIColor(
                red = backgroundColor.red.toDouble(),
                green = backgroundColor.green.toDouble(),
                blue = backgroundColor.blue.toDouble(),
                alpha = backgroundColor.alpha.toDouble()
            )
            config.baseForegroundColor = UIColor(
                red = textColor.red.toDouble(),
                green = textColor.green.toDouble(),
                blue = textColor.blue.toDouble(),
                alpha = textColor.alpha.toDouble()
            )
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
    UIKitView(
        factory = {
            val button = UIButton.buttonWithType(UIButtonTypeSystem)
            val uiColor = UIColor(
                red = textColor.red.toDouble(),
                green = textColor.green.toDouble(),
                blue = textColor.blue.toDouble(),
                alpha = textColor.alpha.toDouble()
            )
            button.setTitleColor(uiColor, UIControlStateNormal)
            val action = UIAction.actionWithHandler { _ -> onClick() }
            button.addAction(action, forControlEvents = UIControlEventTouchUpInside)
            button
        },
        update = { button ->
            val uiColor = UIColor(
                red = textColor.red.toDouble(),
                green = textColor.green.toDouble(),
                blue = textColor.blue.toDouble(),
                alpha = textColor.alpha.toDouble()
            )
            button.setTitle(text, UIControlStateNormal)
            button.setTitleColor(uiColor, UIControlStateNormal)
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
    UIKitView(
        factory = {
            val button = UIButton.buttonWithType(UIButtonTypeSystem)
            button.layer.borderWidth = 1.0
            button.layer.cornerRadius = 12.0

            val uiColor = UIColor(
                red = textColor.red.toDouble(),
                green = textColor.green.toDouble(),
                blue = textColor.blue.toDouble(),
                alpha = textColor.alpha.toDouble()
            )
            button.layer.borderColor = uiColor.CGColor
            button.setTitleColor(uiColor, UIControlStateNormal)

            val action = UIAction.actionWithHandler { _ -> onClick() }
            button.addAction(action, forControlEvents = UIControlEventTouchUpInside)
            button
        },
        update = { button ->
            val uiColor = UIColor(
                red = textColor.red.toDouble(),
                green = textColor.green.toDouble(),
                blue = textColor.blue.toDouble(),
                alpha = textColor.alpha.toDouble()
            )
            button.setTitle(text, UIControlStateNormal)
            button.layer.borderColor = uiColor.CGColor
            button.setTitleColor(uiColor, UIControlStateNormal)
        },
        modifier = modifier
    )
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun AppSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier
) {
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
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF34C759),
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFE9E9EB),
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}