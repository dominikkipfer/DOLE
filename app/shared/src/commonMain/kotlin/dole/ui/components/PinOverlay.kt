package dole.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dole.ui.metrics.CardLayoutMetrics

@Composable
fun PinOverlay(
    title: String,
    pinLength: Int,
    isError: Boolean,
    shakeOffset: Float,
    metrics: CardLayoutMetrics,
    hideDots: Boolean = false
) {
    PinStatusDisplay(
        title = title,
        pinLength = pinLength,
        isError = isError,
        shakeOffset = shakeOffset,
        textSize = metrics.overlayTextSize,
        dotSize = metrics.overlayDotSize,
        spacing = metrics.overlaySpacing,
        contentColor = Color.Black,
        hideDots = hideDots
    )
}

@Composable
fun PinStatusDisplay(
    title: String,
    pinLength: Int,
    isError: Boolean,
    shakeOffset: Float,
    textSize: TextUnit,
    dotSize: Dp,
    spacing: Dp,
    contentColor: Color,
    hideDots: Boolean = false
) {
    val errorColor = MaterialTheme.colorScheme.error
    val dotColor = if (isError) errorColor else contentColor
    val titleColor = if (isError) errorColor else contentColor.copy(alpha = 0.85f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().offset(x = shakeOffset.dp)
    ) {
        Text(
            text = title,
            color = titleColor,
            fontSize = textSize,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 2.sp
        )

        if (!hideDots) {
            Spacer(Modifier.height(spacing))

            Row(horizontalArrangement = Arrangement.spacedBy(dotSize), verticalAlignment = Alignment.CenterVertically) {
                repeat(4) { i ->
                    Box(
                        modifier = Modifier.size(dotSize).background(
                            color = if (i < pinLength) dotColor else dotColor.copy(alpha = 0.2f),
                            shape = CircleShape
                        )
                    )
                }
            }
        }
    }
}
