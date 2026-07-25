package dole.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import com.mohamedrejeb.calf.ui.gesture.adaptiveClickable

@Composable
fun ResizableNumPad(buttonSize: Dp, textSize: TextUnit, onDigit: (String) -> Unit, onDelete: () -> Unit) {
    val spacing = buttonSize * 0.25f
    val digitContainer = MaterialTheme.colorScheme.surfaceVariant
    val digitContent = MaterialTheme.colorScheme.onSurfaceVariant

    Column(verticalArrangement = Arrangement.spacedBy(spacing), horizontalAlignment = Alignment.CenterHorizontally) {
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("", "0", "DEL")
        )

        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                for (key in row) {
                    if (key == "DEL") {
                        Box(
                            modifier = Modifier
                                .size(buttonSize)
                                .clip(CircleShape)
                                .adaptiveClickable(shape = CircleShape, onClick = onDelete),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Backspace,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.size(buttonSize * 0.4f)
                            )
                        }
                    } else if (key.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(buttonSize)
                                .clip(CircleShape)
                                .background(digitContainer)
                                .adaptiveClickable(shape = CircleShape) { onDigit(key) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = key, fontSize = textSize, fontWeight = FontWeight.SemiBold, color = digitContent)
                        }
                    } else {
                        Spacer(Modifier.size(buttonSize))
                    }
                }
            }
        }
    }
}
