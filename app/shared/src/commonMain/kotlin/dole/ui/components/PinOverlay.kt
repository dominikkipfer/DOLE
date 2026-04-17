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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dole.ui.metrics.CardLayoutMetrics

@Composable
fun PinOverlay(title: String, pinLength: Int, isError: Boolean, shakeOffset: Float, metrics: CardLayoutMetrics) {
    val defaultColor = MaterialTheme.colorScheme.onBackground
    val errorColor = MaterialTheme.colorScheme.error

    val contentColor = if (isError) errorColor else defaultColor
    val titleColor = if (isError) errorColor else defaultColor.copy(alpha = 0.85f)

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