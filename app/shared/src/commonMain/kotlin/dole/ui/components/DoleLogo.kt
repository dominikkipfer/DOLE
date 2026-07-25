package dole.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.mohamedrejeb.calf.ui.gesture.adaptiveClickable
import dole.app.shared.generated.resources.Res
import dole.app.shared.generated.resources.dole_logo
import org.jetbrains.compose.resources.painterResource

@Composable
fun DoleLogo(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null
) {
    Icon(
        painter = painterResource(Res.drawable.dole_logo),
        contentDescription = contentDescription,
        tint = tint,
        modifier = if (onClick == null) {
            modifier
        } else {
            modifier.adaptiveClickable(shape = RoundedCornerShape(20), indication = null, onClick = onClick)
        }
    )
}
