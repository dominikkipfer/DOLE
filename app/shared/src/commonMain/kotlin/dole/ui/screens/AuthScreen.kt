package dole.ui.screens

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import dole.data.models.StoredAccount
import dole.ui.components.ResizableNumPad
import dole.ui.components.WalletCard
import dole.ui.components.PinOverlay
import dole.ui.components.triggerShakeAnimation
import dole.ui.layouts.SplitLayout
import dole.ui.modifiers.pinInputHandler
import dole.ui.metrics.rememberCardMetrics
import dole.ui.metrics.rememberNumPadMetrics
import dole.utils.rememberSecureStorage

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AuthScreen(
    account: StoredAccount,
    physicallyConnectedAccount: StoredAccount?,
    isBiometricsEnabled: Boolean,
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

    val secureStorage = rememberSecureStorage()
    var showNumPad by remember { mutableStateOf(!isBiometricsEnabled || !secureStorage.isBiometricSupported) }

    LaunchedEffect(isError) {
        if (isError) {
            showNumPad = true
            shakeOffset.triggerShakeAnimation()
            pin = ""
            onErrorShown()
        }
    }

    LaunchedEffect(Unit) {
        if (isBiometricsEnabled && secureStorage.isBiometricSupported && !isError) {
            showNumPad = false
            val pinResult = secureStorage.getPinWithBiometrics(account.id)
            if (pinResult != null) {
                onLogin(pinResult)
            } else {
                showNumPad = true
                focusRequester.requestFocus()
            }
        } else {
            focusRequester.requestFocus()
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

    val isCardPresent = physicallyConnectedAccount?.id == account.id

    SplitLayout(
        modifier = Modifier.pinInputHandler(
            focusRequester = focusRequester,
            enabled = !isError && showNumPad,
            onDigit = { addDigit(it) },
            onDelete = { removeDigit() },
            onEscape = onCancel
        ),
        cardContent = {
            with(sharedTransitionScope) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val metrics = rememberCardMetrics(maxWidth, maxHeight)
                    val targetWidth = min(maxWidth * 1.1f, (maxHeight - 40.dp).coerceAtLeast(0.dp) * 1.586f)

                    Box(
                        modifier = Modifier
                            .width(targetWidth)
                            .sharedBounds(
                                sharedContentState = rememberSharedContentState(key = "card-${account.id}"),
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
                                PinOverlay(
                                    title = if (isError) "WRONG PIN" else if (!showNumPad) "" else "ENTER PIN",
                                    pinLength = pin.length,
                                    isError = isError,
                                    shakeOffset = shakeOffset.value,
                                    metrics = metrics,
                                    hideDots = !showNumPad
                                )
                            }
                        )
                    }
                }
            }
        },
        inputContent = {
            BoxWithConstraints(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                val metrics = rememberNumPadMetrics(maxWidth, maxHeight)

                if (showNumPad) {
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
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Fingerprint,
                            contentDescription = "Biometrics",
                            modifier = Modifier.size(80.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        bottomContent = {
            TextButton(onClick = onCancel, modifier = Modifier.height(48.dp)) {
                Text("Cancel", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            }
        }
    )
}