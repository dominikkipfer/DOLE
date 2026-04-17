package dole.ui.metrics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp

data class CardLayoutMetrics(
    val actualHeight: Dp,
    val overlayTextSize: TextUnit,
    val overlayDotSize: Dp,
    val overlaySpacing: Dp
)

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