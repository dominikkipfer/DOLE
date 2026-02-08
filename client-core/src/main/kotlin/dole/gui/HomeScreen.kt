package dole.gui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.times
import androidx.compose.ui.zIndex
import dole.wallet.StoredAccount
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun HomeScreen(
    accounts: List<StoredAccount>,
    onAccountClick: (StoredAccount) -> Unit,
    physicallyConnectedAccount: StoredAccount?,
    isOverlayVisible: Boolean,
    initialSelectedAccountId: String? = null,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope
) {
    val backgroundColor = Color(0xFFF2F2F7)

    Box(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val isWideLayout = maxWidth > maxHeight
            val density = LocalDensity.current

            val availableWidth = maxWidth
            val availableHeight = maxHeight

            val maxCardHeight = if (isWideLayout) (availableHeight * 0.5f) else (availableHeight * 0.6f)
            val widthBasedMax = (availableWidth - 40.dp).coerceAtLeast(0.dp)
            val heightBasedMaxWidth = maxCardHeight * 1.586f
            val cardMaxWidth = min(1000.dp, min(widthBasedMax, heightBasedMaxWidth))
            val cardActualHeight = cardMaxWidth / 1.586f

            val verticalPadding = if (isWideLayout) 40.dp else 120.dp
            val verticalRange = (availableHeight - cardActualHeight - verticalPadding).coerceAtLeast(0.dp)
            val horizontalRange = (availableWidth - cardMaxWidth - 40.dp).coerceAtLeast(0.dp)

            val minSpacing = cardActualHeight * 0.2f

            val maxSpacing = (if (isWideLayout) cardMaxWidth else cardActualHeight)
            val itemCount = accounts.size
            val maxScrollIndex = (itemCount - 1).coerceAtLeast(0).toFloat()
            val totalSlack = if (isWideLayout) horizontalRange else verticalRange
            val spacing = if (itemCount > 1) (totalSlack / maxScrollIndex).coerceIn(minSpacing, maxSpacing) else minSpacing

            val isSparseLayout = if (isWideLayout) spacing >= cardMaxWidth else spacing >= cardActualHeight

            val initialScrollIndex = remember(accounts, initialSelectedAccountId) {
                val index = accounts.indexOfFirst { it.id() == initialSelectedAccountId }
                if (index >= 0) index.toFloat() else 0f
            }

            val animatedScrollOffset = remember { Animatable(initialScrollIndex) }
            val coroutineScope = rememberCoroutineScope()
            var wheelSnapJob by remember { mutableStateOf<Job?>(null) }
            val focusRequester = remember { FocusRequester() }
            var isUserScrolling by remember { mutableStateOf(false) }
            var scrollIdleJob by remember { mutableStateOf<Job?>(null) }

            LaunchedEffect(isSparseLayout) {
                if (isSparseLayout) {
                    wheelSnapJob?.cancel()
                    animatedScrollOffset.snapTo(0f)
                }
            }

            LaunchedEffect(Unit) { focusRequester.requestFocus() }

            fun snapToRelative(delta: Int) {
                if (isSparseLayout) return
                val current = animatedScrollOffset.targetValue
                val target = (current + delta).roundToInt().toFloat().coerceIn(0f, maxScrollIndex)
                coroutineScope.launch {
                    isUserScrolling = true
                    scrollIdleJob?.cancel()
                    animatedScrollOffset.animateTo(
                        targetValue = target,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                    )
                    scrollIdleJob = coroutineScope.launch {
                        delay(150)
                        isUserScrolling = false
                    }
                }
            }

            LaunchedEffect(isUserScrolling) {
                if (!isUserScrolling) {
                    val current = animatedScrollOffset.value
                    val target = current.roundToInt().toFloat().coerceIn(0f, maxScrollIndex)
                    animatedScrollOffset.animateTo(
                        targetValue = target,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                    )
                }
            }

            LaunchedEffect(isSparseLayout) {
                if (isSparseLayout) animatedScrollOffset.snapTo(0f)
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester)
                    .focusable()
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            when (event.key) {
                                Key.DirectionDown, Key.DirectionRight -> { snapToRelative(1); true }
                                Key.DirectionUp, Key.DirectionLeft -> { snapToRelative(-1); true }
                                Key.Enter, Key.NumPadEnter -> {
                                    val currentIndex = animatedScrollOffset.targetValue.roundToInt()
                                    if (currentIndex in accounts.indices) onAccountClick(accounts[currentIndex])
                                    true
                                }
                                else -> false
                            }
                        } else false
                    }
                    .draggable(
                        orientation = if (isWideLayout) Orientation.Horizontal else Orientation.Vertical,
                        state = rememberDraggableState { delta ->
                            if (isSparseLayout) return@rememberDraggableState
                            isUserScrolling = true
                            scrollIdleJob?.cancel()
                            scrollIdleJob = coroutineScope.launch {
                                delay(150)
                                isUserScrolling = false
                            }
                            val sensitivity = if(isWideLayout) 0.006f else 0.003f
                            val newOffset = animatedScrollOffset.value - (delta * sensitivity)
                            val constrainedOffset = newOffset.coerceIn(0f, maxScrollIndex)
                            coroutineScope.launch { animatedScrollOffset.snapTo(constrainedOffset) }
                        },
                        onDragStopped = { velocity ->
                            if (isSparseLayout) return@draggable
                            isUserScrolling = true
                            scrollIdleJob?.cancel()
                            val current = animatedScrollOffset.value
                            val target = (current + (velocity * -0.0005f)).roundToInt().toFloat().coerceIn(0f, maxScrollIndex)
                            coroutineScope.launch {
                                animatedScrollOffset.animateTo(
                                    targetValue = target,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                                scrollIdleJob = coroutineScope.launch {
                                    delay(150)
                                    isUserScrolling = false
                                }
                            }
                        }
                    )
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Scroll && event.changes.isNotEmpty()) {
                                    if (isSparseLayout) continue
                                    event.changes.forEach { it.consume() }

                                    isUserScrolling = true
                                    scrollIdleJob?.cancel()
                                    scrollIdleJob = coroutineScope.launch {
                                        delay(150)
                                        isUserScrolling = false
                                    }

                                    val scrollDelta = event.changes.last().scrollDelta
                                    val deltaAmount = if (isWideLayout) scrollDelta.x + scrollDelta.y else scrollDelta.y

                                    val current = animatedScrollOffset.value
                                    val wheelStep = when {
                                        deltaAmount > 0f -> 1f
                                        deltaAmount < 0f -> -1f
                                        else -> 0f
                                    }

                                    val atStart = current <= 0f
                                    val atEnd = current >= maxScrollIndex
                                    val isScrollingOutwardAtStart = atStart && wheelStep < 0f
                                    val isScrollingOutwardAtEnd = atEnd && wheelStep > 0f

                                    if (isScrollingOutwardAtStart || isScrollingOutwardAtEnd || wheelStep == 0f) continue

                                    val newOffset = (current + wheelStep).coerceIn(0f, maxScrollIndex)
                                    coroutineScope.launch { animatedScrollOffset.snapTo(newOffset) }

                                    wheelSnapJob?.cancel()
                                    wheelSnapJob = coroutineScope.launch {
                                        delay(100)
                                        val snapped = animatedScrollOffset.value.roundToInt().toFloat().coerceIn(0f, maxScrollIndex)
                                        animatedScrollOffset.animateTo(
                                            targetValue = snapped,
                                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                        )
                                        scrollIdleJob?.cancel()
                                        scrollIdleJob = coroutineScope.launch {
                                            delay(150)
                                            isUserScrolling = false
                                        }
                                    }
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                val scrollValue = animatedScrollOffset.value
                val centerIndex = (itemCount - 1).coerceAtLeast(0) / 2f
                val visualScrollValue = if (isSparseLayout) centerIndex else scrollValue.coerceIn(0f, maxScrollIndex)
                val scrollProgress = if (maxScrollIndex > 0f) scrollValue / maxScrollIndex else 0.5f
                val globalYOffset = if (!isWideLayout && !isSparseLayout) (scrollProgress - 0.5f) * verticalRange else 0.dp
                val globalXOffset = if (isWideLayout && !isSparseLayout) (scrollProgress - 0.5f) * horizontalRange else 0.dp

                accounts.forEachIndexed { index, acc ->
                    val isInserted = physicallyConnectedAccount?.id() == acc.id()
                    val delta = index - visualScrollValue

                    val cardExtent = if (isWideLayout) cardMaxWidth else cardActualHeight
                    val coverageRatio = (1f - (spacing / cardExtent)).coerceIn(0f, 1f)

                    val showEdgeLabels = if (abs(delta) <= 0.6f) {
                        false
                    } else {
                        val baseThreshold = if (isWideLayout) if (delta < 0f) 0.5f else 0.2f else 0.25f
                        val distanceFactor = (abs(delta) - 1f).coerceAtLeast(0f) * 0.014f
                        val individualThreshold = (baseThreshold + distanceFactor)

                        coverageRatio > individualThreshold
                    }

                    val edgePlacement = if (isWideLayout) {
                        if (delta < 0f) EdgeLabelPlacement.Left else EdgeLabelPlacement.Right
                    } else {
                        if (delta < 0f) EdgeLabelPlacement.Top else EdgeLabelPlacement.Bottom
                    }

                    if (delta > -20f && delta < 20f) {
                        val zIndex = 100f - abs(delta)
                        val scale = if (isSparseLayout) 1f else (1f - (abs(delta) * 0.05f)).coerceAtLeast(0.85f)
                        val alpha = 1f

                        val modifier = if (isWideLayout) {
                            val localXShift = delta * spacing
                            val finalX = localXShift + globalXOffset

                            Modifier
                                .zIndex(zIndex)
                                .graphicsLayer {
                                    this.scaleX = scale
                                    this.scaleY = scale
                                    this.alpha = alpha
                                    this.cameraDistance = 30f * density.density
                                }
                                .offset { androidx.compose.ui.unit.IntOffset(x = finalX.roundToPx(), y = 0) }
                        } else {
                            val localYShift = delta * spacing
                            val portraitTopOffset = 30.dp
                            val finalY = localYShift + globalYOffset + portraitTopOffset

                            Modifier
                                .zIndex(zIndex)
                                .graphicsLayer {
                                    this.scaleX = scale
                                    this.scaleY = scale
                                    this.alpha = alpha
                                    this.cameraDistance = 20f * density.density
                                }
                                .offset { androidx.compose.ui.unit.IntOffset(x = 0, y = finalY.roundToPx()) }
                        }

                        val applySharedBounds = @Composable { modifier: Modifier, id: String ->
                            with(sharedTransitionScope) {
                                modifier.sharedBounds(
                                    sharedContentState = rememberSharedContentState(key = "card-$id"),
                                    animatedVisibilityScope = animatedVisibilityScope,
                                    boundsTransform = { _, _ -> tween(500) },
                                    renderInOverlayDuringTransition = false
                                )
                            }
                        }

                        Box(modifier = Modifier.widthIn(max = cardMaxWidth).fillMaxWidth().then(modifier)) {
                            WalletCard(
                                account = acc,
                                modifier = applySharedBounds(Modifier.fillMaxWidth(), acc.id()),
                                isOnline = isInserted,
                                showInlineLabels = !showEdgeLabels,
                                showEdgeLabels = showEdgeLabels,
                                edgeLabelPlacement = edgePlacement,
                                rotateEdgeLabels = isWideLayout,
                                onClick = {
                                    onAccountClick(acc)
                                }
                            )
                        }
                    }
                }
            }

            if (accounts.isEmpty() && !isOverlayVisible) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Hold your card to your device.", color = Color.Gray)
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(130.dp)
                .background(
                    Brush.verticalGradient(
                        0.0f to backgroundColor,
                        0.65f to backgroundColor,
                        1.0f to backgroundColor.copy(alpha = 0f)
                    )
                )
                .padding(top = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Wallet",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }
        }
    }
}