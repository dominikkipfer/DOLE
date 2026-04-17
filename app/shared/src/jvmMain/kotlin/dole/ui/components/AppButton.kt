package dole.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
actual fun AppButton(text: String, onClick: () -> Unit, modifier: Modifier, backgroundColor: Color, textColor: Color) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = backgroundColor, contentColor = textColor)
    ) {
        Text(text)
    }
}

@Composable
actual fun AppTextButton(text: String, onClick: () -> Unit, modifier: Modifier, textColor: Color) {
    TextButton(onClick = onClick, modifier = modifier) {
        Text(text, color = textColor, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
    }
}

@Composable
actual fun AppOutlinedButton(text: String, onClick: () -> Unit, modifier: Modifier, textColor: Color) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = textColor),
        border = BorderStroke(1.dp, textColor.copy(alpha = 0.5f))
    ) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}