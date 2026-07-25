@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dole.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.calf.ui.ExperimentalCalfUiApi
import com.mohamedrejeb.calf.ui.button.LiquidGlassButton
import com.mohamedrejeb.calf.ui.button.LiquidGlassButtonDefaults
import com.mohamedrejeb.calf.ui.gesture.adaptiveClickable
import kotlinx.cinterop.useContents
import platform.Foundation.NSProcessInfo

private val supportsLiquidGlass: Boolean by lazy {
    NSProcessInfo.processInfo.operatingSystemVersion.useContents { majorVersion >= 26 }
}

@Composable
actual fun SegmentedTabs(options: List<String>, selectedIndex: Int, onSelected: (Int) -> Unit, modifier: Modifier) {
    if (supportsLiquidGlass) {
        GlassSegmentedTabs(options, selectedIndex, onSelected, modifier)
    } else {
        PillSegmentedTabs(options, selectedIndex, onSelected, modifier)
    }
}

@OptIn(ExperimentalCalfUiApi::class)
@Composable
private fun GlassSegmentedTabs(options: List<String>, selectedIndex: Int, onSelected: (Int) -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(50)

    Row(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEachIndexed { index, label ->
            val isSelected = index == selectedIndex

            if (isSelected) {
                LiquidGlassButton(
                    onClick = {},
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    colors = LiquidGlassButtonDefaults.filledButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(text = label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(shape)
                        .adaptiveClickable(indication = null) { onSelected(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
