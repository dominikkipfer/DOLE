package dole.ui.modifiers

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer

fun Modifier.sharedCardEffect(
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    key: String,
    interactionSource: MutableInteractionSource,
    hoverDim: Float
): Modifier = composed {
    with(sharedTransitionScope) {
        this@sharedCardEffect
            .sharedBounds(
                sharedContentState = rememberSharedContentState(key = key),
                animatedVisibilityScope = animatedVisibilityScope,
                boundsTransform = { _, _ -> tween(500) }
            )
            .hoverable(interactionSource)
            .graphicsLayer {
                if (hoverDim > 0f) compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                if (hoverDim > 0f) drawRect(Color.Black, alpha = hoverDim, blendMode = BlendMode.SrcAtop)
            }
    }
}