package dole.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import dole.data.models.StoredAccount

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun NewCardOverlay(
    cardId: String,
    onConnect: () -> Unit,
    onDismiss: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope
) {
    val containerKey = "setup-container-$cardId"
    val cardKey = "card-$cardId"
    val unifiedSpacing = 24.dp

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        with(sharedTransitionScope) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .heightIn(min = 100.dp)
                    .sharedBounds(
                        rememberSharedContentState(key = containerKey),
                        animatedVisibilityScope = animatedVisibilityScope,
                        boundsTransform = { _, _ -> tween(500) }
                    ),
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 32.dp
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(unifiedSpacing),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    OverlayHeader(onDismiss)
                    Spacer(modifier = Modifier.height(unifiedSpacing))
                    Box(modifier = Modifier.weight(1f, fill = false)) {
                        OverlayCardSection(
                            cardId = cardId,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            cardKey = cardKey
                        )
                    }
                    Spacer(modifier = Modifier.height(unifiedSpacing))
                    OverlayButton(onConnect)
                }
            }
        }
    }
}

@Composable
private fun OverlayHeader(onDismiss: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "New Card",
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 32.sp),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.Center)
        )
        IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterEnd).size(32.dp)) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun OverlayCardSection(
    cardId: String,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    cardKey: String
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val aspectRatio = 1.586f
        val heightBasedWidth = maxHeight * aspectRatio
        val targetWidth = min(maxWidth * 0.9f, heightBasedWidth)

        if (targetWidth > 20.dp) {
            with(sharedTransitionScope) {
                Box(
                    modifier = Modifier.width(targetWidth).sharedBounds(
                        sharedContentState = rememberSharedContentState(key = cardKey),
                        animatedVisibilityScope = animatedVisibilityScope,
                        boundsTransform = { _, _ -> tween(500) }
                    )
                ) {
                    val dummyAccount = remember(cardId) { StoredAccount(cardId, "", "") }
                    WalletCard(
                        account = dummyAccount,
                        isOnline = true,
                        showFullId = false,
                        showInlineLabels = true,
                        modifier = Modifier.fillMaxWidth().aspectRatio(aspectRatio)
                    )
                }
            }
        }
    }
}

@Composable
private fun OverlayButton(onConnect: () -> Unit) {
    AppButton(
        text = "Connect",
        onClick = onConnect,
        modifier = Modifier.fillMaxWidth().height(56.dp)
    )
}