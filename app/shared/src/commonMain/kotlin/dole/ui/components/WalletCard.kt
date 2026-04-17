package dole.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dole.data.models.StoredAccount

val LocalCardPulse = compositionLocalOf { 0f }

@Suppress("unused")
enum class EdgeLabelPlacement { Top, Bottom, Left, Right }

@Composable
fun WalletCard(
    account: StoredAccount,
    modifier: Modifier = Modifier,
    isOnline: Boolean = false,
    showFullId: Boolean = false,
    showInlineLabels: Boolean = true,
    showEdgeLabels: Boolean = false,
    edgeLabelPlacement: EdgeLabelPlacement = EdgeLabelPlacement.Bottom,
    rotateEdgeLabels: Boolean = false,
    onClick: (() -> Unit)? = null,
    onIdClick: (() -> Unit)? = null,
    overlayContent: @Composable (BoxScope.() -> Unit)? = null
) {
    val baseColor = Color(0xFFF7F7F7)
    val glowingBlue = Color(0xFF00B0FF)

    BoxWithConstraints(modifier = modifier) {
        val baseWidth = 320f
        val actualWidth = maxWidth.value
        val scaleFactor = (actualWidth / baseWidth).coerceAtLeast(0.1f)

        val effectPadding = 16.dp * scaleFactor
        val cardContentWidth = (maxWidth - (effectPadding * 2))
        val cardContentHeight = (cardContentWidth / 1.586f).coerceAtLeast(0.dp)

        val edgePaddingStart = 20.dp * scaleFactor
        val edgePaddingTop = 20.dp * scaleFactor
        val edgePaddingBottom = 18.dp * scaleFactor
        val edgeLandscapeInset = 8.dp * scaleFactor

        val nameFontSize = 15.sp * scaleFactor
        val idFontSize = 9.sp * scaleFactor
        val idLetterSpacing = 1.5.sp * scaleFactor

        val cardCornerRadius = 14.dp * scaleFactor
        val cardShape = RoundedCornerShape(cardCornerRadius)

        val isOverlayActive = overlayContent != null

        val shouldBlurChip = isOverlayActive || (showEdgeLabels && edgeLabelPlacement == EdgeLabelPlacement.Left)
        val blurRadius = if (shouldBlurChip) 5.dp * scaleFactor else 0.dp

        val pulseProgress = LocalCardPulse.current
        val shadowColor = if (isOnline) glowingBlue else Color.Black
        val shadowElevation = if (isOnline) (6.dp * scaleFactor) else (2.dp * scaleFactor)

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isOnline) {
                FrequencyRingDrawing(
                    modifier = Modifier.matchParentSize(),
                    progress = pulseProgress,
                    color = glowingBlue,
                    scaleFactor = scaleFactor,
                    initialPadding = effectPadding,
                    initialCornerRadius = cardCornerRadius
                )

                val secondRingProgress = (pulseProgress + 0.5f) % 1.0f
                FrequencyRingDrawing(
                    modifier = Modifier.matchParentSize(),
                    progress = secondRingProgress,
                    color = glowingBlue,
                    scaleFactor = scaleFactor,
                    initialPadding = effectPadding,
                    initialCornerRadius = cardCornerRadius
                )
            }

            Box(
                modifier = Modifier
                    .padding(effectPadding)
                    .fillMaxWidth()
                    .aspectRatio(1.586f)
                    .shadow(elevation = shadowElevation, shape = cardShape, spotColor = shadowColor, ambientColor = shadowColor)
                    .clip(cardShape)
                    .background(baseColor)
                    .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().then(if (blurRadius > 0.dp) Modifier.blur(blurRadius) else Modifier)
                ) {
                    CardChip(scaleFactor)
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    if (showInlineLabels) {
                        CardName(account.name, scaleFactor)
                        CardId(account, showFullId, scaleFactor, onIdClick)
                    }

                    if (showEdgeLabels) {
                        if (!rotateEdgeLabels) {
                            val edgeAlignment = when (edgeLabelPlacement) {
                                EdgeLabelPlacement.Top -> Alignment.TopCenter
                                EdgeLabelPlacement.Bottom -> Alignment.BottomCenter
                                else -> Alignment.Center
                            }
                            val topPad = if (edgeLabelPlacement == EdgeLabelPlacement.Top) edgePaddingTop else 0.dp
                            val bottomPad = if (edgeLabelPlacement == EdgeLabelPlacement.Bottom) edgePaddingBottom else 0.dp

                            Row(
                                modifier = Modifier
                                    .align(edgeAlignment)
                                    .padding(start = edgePaddingStart, end = edgePaddingStart, top = topPad, bottom = bottomPad)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                EdgeLabelTexts(
                                    account = account,
                                    showFullId = showFullId,
                                    nameFontSize = nameFontSize,
                                    idFontSize = idFontSize,
                                    idLetterSpacing = idLetterSpacing,
                                    placement = edgeLabelPlacement
                                )
                            }
                        } else {
                            val isLeft = edgeLabelPlacement == EdgeLabelPlacement.Left
                            val edgeAlignment = if (isLeft) Alignment.BottomStart else Alignment.BottomEnd
                            val rotation = if (isLeft) -90f else 90f
                            val origin = if (isLeft) TransformOrigin(0f, 1f) else TransformOrigin(1f, 1f)
                            val rotatedWidth = (cardContentHeight - edgePaddingTop - edgePaddingBottom).coerceAtLeast(0.dp)

                            Row(
                                modifier = Modifier
                                    .align(edgeAlignment)
                                    .padding(
                                        bottom = edgePaddingBottom,
                                        start = if (isLeft) edgePaddingStart + edgeLandscapeInset else 0.dp,
                                        end = if (!isLeft) edgePaddingStart + edgeLandscapeInset else 0.dp
                                    )
                                    .width(rotatedWidth)
                                    .graphicsLayer {
                                        rotationZ = rotation
                                        transformOrigin = origin
                                        clip = false
                                    },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                EdgeLabelTexts(
                                    account = account,
                                    showFullId = showFullId,
                                    nameFontSize = nameFontSize,
                                    idFontSize = idFontSize,
                                    idLetterSpacing = idLetterSpacing,
                                    placement = edgeLabelPlacement
                                )
                            }
                        }
                    }
                }

                if (overlayContent != null) {
                    Box(
                        modifier = Modifier.align(BiasAlignment(0f, 0f)).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        overlayContent()
                    }
                }
            }
        }
    }
}

@Composable
fun FrequencyRingDrawing(
    modifier: Modifier = Modifier,
    progress: Float,
    color: Color,
    scaleFactor: Float,
    initialPadding: Dp,
    initialCornerRadius: Dp
) {
    val ringStrokeWidth = 2.dp * scaleFactor
    val maxExpansion = 16.dp * scaleFactor
    val ringBlurRadius = 3.dp * scaleFactor

    val maxAlpha = 0.7f
    val fadeInThreshold = 0.2f
    val fadeOutThreshold = 0.8f

    val alpha = when {
        progress < fadeInThreshold -> (progress / fadeInThreshold) * maxAlpha
        progress < fadeOutThreshold -> {
            val fadeProgress = (progress - fadeInThreshold) / (fadeOutThreshold - fadeInThreshold)
            (1f - fadeProgress) * maxAlpha
        }
        else -> 0f
    }

    Canvas(modifier = modifier.blur(ringBlurRadius)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val baseCardWidth = size.width - (2 * initialPadding.toPx())
        val baseCardHeight = baseCardWidth / 1.586f

        val startOffset = ((-2).dp * scaleFactor).toPx()
        val totalDistance = maxExpansion.toPx() - startOffset
        val currentExpansion = startOffset + (totalDistance * progress)
        val currentRadius = initialCornerRadius.toPx() + currentExpansion

        val ringWidth = baseCardWidth + (2 * currentExpansion)
        val ringHeight = baseCardHeight + (2 * currentExpansion)

        val topLeft = Offset(
            x = center.x - (ringWidth / 2f),
            y = center.y - (ringHeight / 2f)
        )

        if (alpha > 0f) {
            drawRoundRect(
                color = color.copy(alpha = alpha),
                topLeft = topLeft,
                size = Size(ringWidth, ringHeight),
                cornerRadius = CornerRadius(currentRadius, currentRadius),
                style = Stroke(width = ringStrokeWidth.toPx())
            )
        }
    }
}

@Composable
fun BoxScope.CardChip(scaleFactor: Float) {
    val cornerRadius = 8.dp * scaleFactor
    val borderSize = (0.5f * scaleFactor).dp

    Box(
        modifier = Modifier
            .align(BiasAlignment(-0.78f, -0.05f))
            .fillMaxWidth(0.14f)
            .aspectRatio(1.111f)
            .border(width = borderSize, color = Color.Black, shape = RoundedCornerShape(cornerRadius))
            .clip(RoundedCornerShape(cornerRadius))
    ) {
        SimpleGoldChip()
    }
}

@Composable
fun BoxScope.CardName(name: String, scaleFactor: Float) {
    Text(
        text = name,
        color = Color.Black.copy(alpha = 0.8f),
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp * scaleFactor,
        maxLines = 1,
        modifier = Modifier.align(Alignment.TopStart).padding(top = 20.dp * scaleFactor, start = 20.dp * scaleFactor)
    )
}

@Composable
fun BoxScope.CardId(account: StoredAccount, showFullId: Boolean, scaleFactor: Float, onIdClick: (() -> Unit)?) {
    val idText = if (showFullId) account.id else "•••• ${account.id.takeLast(4)}"
    Text(
        text = idText,
        color = Color.Black.copy(alpha = 0.6f),
        fontSize = 9.sp * scaleFactor,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.5.sp * scaleFactor,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(bottom = 18.dp * scaleFactor, start = 20.dp * scaleFactor)
            .then(if (onIdClick != null) Modifier.clickable(interactionSource = null, indication = null) {
                onIdClick()
            } else Modifier)
    )
}

@Composable
private fun EdgeLabelTexts(
    account: StoredAccount,
    showFullId: Boolean,
    nameFontSize: TextUnit,
    idFontSize: TextUnit,
    idLetterSpacing: TextUnit,
    placement: EdgeLabelPlacement
) {
    val name = account.name
    var weightedLength = 0.0
    name.forEach { char ->
        weightedLength += when (char) {
            'W', 'w', 'M', 'm', '@', '%' -> 1.5
            'A', 'B', 'C', 'D', 'G', 'H', 'K', 'N', 'O', 'Q', 'R', 'S', 'U', 'V', 'X', 'Y', 'Z' -> 1.2
            'i', 'l', 'j', 't', 'f', 'I', '1', '.', ',', ' ' -> 0.6
            else -> 1.0
        }
    }

    val isPortrait = placement == EdgeLabelPlacement.Top || placement == EdgeLabelPlacement.Bottom
    val capacity = if (isPortrait) 22.0 else 11.0

    val dynamicFontSize = if (weightedLength > capacity) {
        val scale = capacity / weightedLength
        nameFontSize * scale.toFloat()
    } else {
        nameFontSize
    }

    Text(
        text = name,
        color = Color.Black.copy(alpha = 0.8f),
        fontWeight = FontWeight.Bold,
        fontSize = dynamicFontSize,
        maxLines = 1
    )
    Text(
        text = if (showFullId) account.id else "•••• ${account.id.takeLast(4)}",
        color = Color.Black.copy(alpha = 0.6f),
        fontWeight = FontWeight.Medium,
        fontSize = idFontSize,
        letterSpacing = idLetterSpacing
    )
}

@Composable
fun SimpleGoldChip() {
    val goldLight = Color(0xFFFDD835)
    val goldDark = Color(0xFFFBC02D)

    Box(
        modifier = Modifier.fillMaxSize().background(brush = Brush.linearGradient(
            colors = listOf(goldLight, goldDark, goldLight),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        ))
    )
}