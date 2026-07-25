package dole.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dole.ui.screens.FrontEllipsizedText
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun EntryRow(
    title: String,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    titleEllipsized: Boolean = false,
    icon: ImageVector? = null,
    iconTint: Color = Color.Unspecified,
    iconRotated: Boolean = false,
    timestamp: String = "",
    onTitleClick: (() -> Unit)? = null,
    subtitle: (@Composable () -> Unit)? = null
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(28.dp).graphicsLayer { if (iconRotated) rotationZ = 180f }
                )
                Spacer(Modifier.width(16.dp))
            }

            Column(Modifier.weight(1f)) {
                if (timestamp.isNotEmpty()) {
                    Text(
                        text = timestamp,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val titleStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                if (titleEllipsized) {
                    FrontEllipsizedText(
                        text = title,
                        style = titleStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().tapToCopy(onTitleClick)
                    )
                } else {
                    Text(
                        title,
                        style = titleStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.tapToCopy(onTitleClick)
                    )
                }
                subtitle?.invoke()
            }

            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun TransactionAmount(text: String, color: Color) {
    Text(text, fontWeight = FontWeight.Bold, color = color)
}

@Composable
private fun Modifier.tapToCopy(onClick: (() -> Unit)?): Modifier {
    if (onClick == null) return this
    val interaction = remember { MutableInteractionSource() }
    return clickable(interaction, indication = null) { onClick() }
}

@Composable
fun TransactionPeerSubtitle(id: String, prefix: String = "", onClick: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.tapToCopy(onClick).fillMaxWidth()) {
        if (prefix.isNotEmpty()) {
            Text(prefix, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FrontEllipsizedText(
            text = id,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun TransactionPeerPairSubtitle(
    fromId: String,
    toId: String,
    onFromClick: (() -> Unit)? = null,
    onToClick: (() -> Unit)? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        FrontEllipsizedText(
            text = fromId,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).tapToCopy(onFromClick)
        )
        Text(" → ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FrontEllipsizedText(
            text = toId,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).tapToCopy(onToClick)
        )
    }
}

@Composable
fun TransactionPlainSubtitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1
    )
}

fun formatTransactionTimestamp(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return ""
    val local = Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${local.day.pad()}.${(local.month.ordinal + 1).pad()}.${local.year}  ${local.hour.pad()}:${local.minute.pad()}"
}

private fun Int.pad(): String = if (this < 10) "0$this" else toString()
