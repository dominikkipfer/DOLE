package dole.gui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import dole.wallet.StoredAccount

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AuthScreen(
    account: StoredAccount,
    physicallyConnectedAccount: StoredAccount?,
    isError: Boolean,
    onLogin: (String) -> Unit,
    onCancel: () -> Unit,
    onErrorShown: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope
) {
    var pin by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val shakeOffset = remember { Animatable(0f) }

    LaunchedEffect(isError) {
        if (isError) {
            shakeOffset.animateTo(targetValue = 0f, animationSpec = keyframes {
                durationMillis = 400
                0f at 0
                (-20f) at 50
                20f at 100
                (-20f) at 150
                20f at 200
                0f at 400
            })
            pin = ""
            onErrorShown()
        }
    }

    fun addDigit(digit: String) {
        if (pin.length < 4 && !isError) {
            pin += digit
            if (pin.length == 4) onLogin(pin)
        }
    }

    fun removeDigit() {
        if (pin.isNotEmpty() && !isError) pin = pin.dropLast(1)
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val isCardPresent = physicallyConnectedAccount?.id() == account.id()

    AuthSplitLayout(
        modifier = Modifier
            .pinInputHandler(
                focusRequester = focusRequester,
                enabled = !isError,
                onDigit = { addDigit(it) },
                onDelete = { removeDigit() },
                onEscape = onCancel
            ),
        cardContent = {
            with(sharedTransitionScope) {
                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    val metrics = rememberCardMetrics(maxWidth, maxHeight)
                    val heightBasedWidth = (maxHeight - 40.dp).coerceAtLeast(0.dp) * 1.586f
                    val targetWidth = min(maxWidth * 1.1f, heightBasedWidth)

                    Box(
                        modifier = Modifier
                            .width(targetWidth)
                            .sharedBounds(
                                sharedContentState = rememberSharedContentState(key = "card-${account.id()}"),
                                animatedVisibilityScope = animatedVisibilityScope,
                                boundsTransform = { _, _ -> tween(500) },
                                renderInOverlayDuringTransition = false
                            )
                    ) {
                        WalletCard(
                            account = account,
                            modifier = Modifier.fillMaxWidth(),
                            isOnline = isCardPresent,
                            showFullId = false,
                            overlayContent = {
                                val titleText = if (isError) "WRONG PIN" else "ENTER PIN"
                                PinOverlay(titleText, pin.length, isError, shakeOffset.value, metrics)
                            }
                        )
                    }
                }
            }
        },
        inputContent = {
            BoxWithConstraints(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                val metrics = rememberNumPadMetrics(maxWidth, maxHeight)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(metrics.buttonSize * 0.25f),
                    modifier = Modifier.widthIn(max = 400.dp)
                ) {
                    ResizableNumPad(
                        buttonSize = metrics.buttonSize,
                        textSize = metrics.textSize,
                        onDigit = { addDigit(it) },
                        onDelete = { removeDigit() }
                    )
                }
            }
        },
        bottomContent = {
            TextButton(onClick = onCancel, modifier = Modifier.height(48.dp)) {
                Text("Cancel", color = Color.Red, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            }
        }
    )
}