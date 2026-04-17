package dole.ui.modifiers

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

fun Modifier.pinInputHandler(
    focusRequester: FocusRequester,
    enabled: Boolean,
    onDigit: (String) -> Unit,
    onDelete: () -> Unit,
    onEscape: () -> Unit
): Modifier = this
    .focusRequester(focusRequester)
    .onPreviewKeyEvent { event ->
        if (enabled && event.type == KeyEventType.KeyUp) {
            when (event.key) {
                Key.Zero, Key.NumPad0 -> onDigit("0")
                Key.One, Key.NumPad1 -> onDigit("1")
                Key.Two, Key.NumPad2 -> onDigit("2")
                Key.Three, Key.NumPad3 -> onDigit("3")
                Key.Four, Key.NumPad4 -> onDigit("4")
                Key.Five, Key.NumPad5 -> onDigit("5")
                Key.Six, Key.NumPad6 -> onDigit("6")
                Key.Seven, Key.NumPad7 -> onDigit("7")
                Key.Eight, Key.NumPad8 -> onDigit("8")
                Key.Nine, Key.NumPad9 -> onDigit("9")
                Key.Backspace -> onDelete()
                Key.Escape -> onEscape()
                else -> return@onPreviewKeyEvent false
            }
            true
        } else {
            false
        }
    }