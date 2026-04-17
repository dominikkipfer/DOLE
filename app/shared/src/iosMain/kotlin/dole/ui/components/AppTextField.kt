package dole.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIColor
import platform.UIKit.UITextBorderStyleRoundedRect
import platform.UIKit.UITextField

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
actual fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier,
    isPassword: Boolean,
    leadingIcon: @Composable (() -> Unit)?,
    trailingIcon: @Composable (() -> Unit)?,
    containerColor: Color,
    shape: Shape,
    singleLine: Boolean,
    readOnly: Boolean,
    textStyle: TextStyle
) {
    UIKitView(
        factory = {
            val textField = UITextField()
            textField.borderStyle = UITextBorderStyleRoundedRect
            textField.placeholder = placeholder
            textField.secureTextEntry = isPassword
            textField.backgroundColor = containerColor.toUIColor()
            textField.userInteractionEnabled = !readOnly
            textField
        },
        update = { textField ->
            if (textField.text != value) {
                textField.text = value
            }
            textField.backgroundColor = containerColor.toUIColor()
            textField.userInteractionEnabled = !readOnly
        },
        modifier = modifier
    )
}