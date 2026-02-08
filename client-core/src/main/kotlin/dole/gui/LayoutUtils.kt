package dole.gui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

data class CardLayoutMetrics(val actualHeight: Dp, val overlayTextSize: TextUnit, val overlayDotSize: Dp, val overlaySpacing: Dp)

@Composable
fun rememberCardMetrics(availableWidth: Dp, availableHeight: Dp): CardLayoutMetrics {
    return remember(availableWidth, availableHeight) {
        val cardRatio = 1.586f
        val maxUsableHeight = (availableHeight - 40.dp).coerceAtLeast(0.dp)
        val widthBasedOnHeight = maxUsableHeight * cardRatio
        val usedWidth = min(availableWidth * 1.1f, widthBasedOnHeight)
        val actualCardHeight = usedWidth / cardRatio

        CardLayoutMetrics(
            actualHeight = actualCardHeight,
            overlayTextSize = (actualCardHeight.value * 0.15f).coerceAtLeast(14f).sp,
            overlayDotSize = (actualCardHeight * 0.1f).coerceAtLeast(6.dp),
            overlaySpacing = (actualCardHeight * 0.08f)
        )
    }
}

data class NumPadMetrics(val buttonSize: Dp, val textSize: TextUnit)

@Composable
fun rememberNumPadMetrics(availableWidth: Dp, availableHeight: Dp): NumPadMetrics {
    return remember(availableWidth, availableHeight) {
        val sizeW = (availableWidth - 32.dp) / 3.5f
        val sizeH = availableHeight / 5.5f
        val autoButtonSize = min(sizeW, sizeH).coerceAtMost(85.dp)

        NumPadMetrics(buttonSize = autoButtonSize, textSize = (autoButtonSize.value * 0.45f).sp)
    }
}

@Composable
fun AuthSplitLayout(
    modifier: Modifier = Modifier,
    cardContent: @Composable BoxScope.() -> Unit,
    inputContent: @Composable BoxScope.() -> Unit,
    bottomContent: @Composable BoxScope.() -> Unit
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().background(Color(0xFFF2F2F7)),
        contentAlignment = Alignment.Center
    ) {
        val isWideLayout = maxWidth > maxHeight
        val topPadding = if (isWideLayout) 16.dp else 40.dp
        val cancelButtonHeight = 48.dp

        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (isWideLayout) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                            Box(Modifier.padding(end = 48.dp, top = 24.dp, bottom = 24.dp).fillMaxSize()) {
                                cardContent()
                            }
                        }
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            inputContent()
                        }
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
                        Spacer(modifier = Modifier.height(topPadding))

                        Box(
                            modifier = Modifier.weight(0.4f).fillMaxWidth().padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            cardContent()
                        }

                        Box(
                            modifier = Modifier.weight(0.6f).fillMaxWidth().padding(horizontal = 16.dp),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            inputContent()
                        }
                    }
                }
            }

            Box(
                modifier = Modifier.fillMaxWidth().height(cancelButtonHeight + 12.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                bottomContent()
            }
        }
    }
}

@Composable
fun PinOverlay(title: String, pinLength: Int, isError: Boolean, shakeOffset: Float, metrics: CardLayoutMetrics) {
    val contentColor = if (isError) Color(0xFFFF3B30) else Color.Black
    val titleColor = if (isError) Color(0xFFFF3B30) else Color.Black.copy(alpha = 0.85f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().offset(x = shakeOffset.dp)
    ) {
        Text(
            text = title,
            color = titleColor,
            fontSize = metrics.overlayTextSize,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 2.sp
        )

        Spacer(Modifier.height(metrics.overlaySpacing))

        Row(
            horizontalArrangement = Arrangement.spacedBy(metrics.overlayDotSize),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(4) { i ->
                Box(
                    modifier = Modifier.size(metrics.overlayDotSize).background(
                        if (i < pinLength) contentColor else contentColor.copy(alpha = 0.2f), CircleShape
                    )
                )
            }
        }
    }
}

@Composable
fun NameInputSection(name: String, onNameChange: (String) -> Unit, onNext: () -> Unit, buttonSize: Dp) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val isEnabled = name.isNotBlank()

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = name, onValueChange = { if (it.length <= 20) onNameChange(it) },
            label = { Text("Card Holder Name") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                focusedBorderColor = Color.Black, unfocusedBorderColor = Color.Gray,
                cursorColor = Color.Black, focusedLabelColor = Color.Black, unfocusedLabelColor = Color.Gray,
                focusedTextColor = Color.Black, unfocusedTextColor = Color.Black
            )
        )
        Spacer(Modifier.height(buttonSize * 0.5f))
        Surface(
            onClick = onNext,
            enabled = isEnabled,
            shape = CircleShape,
            color = if (isEnabled) Color.White else Color.White.copy(alpha = 0.5f),
            shadowElevation = if (isEnabled) 2.dp else 0.dp,
            modifier = Modifier.size(buttonSize)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next",
                    tint = if (isEnabled) Color.Black else Color.Gray, modifier = Modifier.size(buttonSize * 0.4f)
                )
            }
        }
    }
}

fun Modifier.pinInputHandler(
    focusRequester: FocusRequester,
    enabled: Boolean = true,
    onDigit: (String) -> Unit,
    onDelete: () -> Unit,
    onEscape: (() -> Unit)? = null
): Modifier = this
    .focusRequester(focusRequester)
    .focusable()
    .onKeyEvent { event ->
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
                Key.Escape -> onEscape?.invoke()
                else -> return@onKeyEvent false
            }
            true
        } else {
            false
        }
    }

suspend fun Animatable<Float, AnimationVector1D>.triggerShakeAnimation() {
    this.animateTo(0f, keyframes {
        durationMillis = 400
        0f at 0
        (-20f) at 50
        20f at 100
        (-20f) at 150
        20f at 200
        0f at 400
    })
}

@Composable
fun SafeOverlay(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .zIndex(999f)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismissRequest() },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.widthIn(min = 280.dp, max = 560.dp).padding(24.dp).clickable(enabled = false) {}
        ) {
            Column(modifier = Modifier.padding(24.dp), content = content)
        }
    }
}