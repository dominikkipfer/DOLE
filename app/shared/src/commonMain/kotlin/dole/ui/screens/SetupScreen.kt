package dole.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dole.data.models.StoredAccount
import dole.ui.components.NameInputSection
import dole.ui.components.PinOverlay
import dole.ui.components.ResizableNumPad
import dole.ui.components.WalletCard
import dole.ui.components.triggerShakeAnimation
import dole.ui.layouts.SplitLayout
import dole.ui.metrics.rememberCardMetrics
import dole.ui.metrics.rememberNumPadMetrics
import dole.ui.modifiers.pinInputHandler
import dole.utils.rememberSecureStorage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SetupScreen(
    cardId: String,
    cardHasPin: Boolean,
    initialName: String = "",
    isLoading: Boolean,
    isSuccess: Boolean,
    isCardConnected: Boolean,
    isError: Boolean = false,
    onErrorShown: () -> Unit = {},
    onRegister: (String, String) -> Unit,
    onCancel: () -> Unit,
    onComplete: (Boolean) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope
) {
    val containerKey = "setup-container-$cardId"
    val cardKey = "card-$cardId"
    var cachedCardId by remember { mutableStateOf(cardId) }
    LaunchedEffect(cardId) { if (cardId != "NULL" && cardId.isNotBlank()) cachedCardId = cardId }

    var name by remember { mutableStateOf(initialName) }
    var pinInput by remember { mutableStateOf("") }
    var firstPin by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableIntStateOf(1) }
    var wantsBiometrics by remember { mutableStateOf(true) }

    val secureStorage = rememberSecureStorage()
    val scope = rememberCoroutineScope()
    var isLocalError by remember { mutableStateOf(false) }
    val shakeOffset = remember { Animatable(0f) }
    val mainFocusRequester = remember { FocusRequester() }

    var isRecoveringFromError by remember { mutableStateOf(false) }

    LaunchedEffect(isError) {
        if (isError) {
            isRecoveringFromError = true
            shakeOffset.triggerShakeAnimation()
            delay(300.milliseconds)

            pinInput = ""
            if (!cardHasPin && step == 3) {
                firstPin = null
                step = 2
            }

            onErrorShown()
            isRecoveringFromError = false
        }
    }

    val previewAccount = remember(name, cachedCardId) {
        StoredAccount(cachedCardId, name.ifEmpty { "YOUR NAME" }, "")
    }

    fun goBack() {
        if (isSuccess) onCancel()
        else if (step > 1) {
            step -= 1
            pinInput = ""
            firstPin = null
        } else onCancel()
    }

    val handleNameSubmit = { step = 2 }

    fun handlePinInput(digit: String) {
        if (isLocalError || isError || isRecoveringFromError || isSuccess || isLoading) return

        if (pinInput.length < 4) {
            pinInput += digit
            if (pinInput.length == 4) {
                if (cardHasPin) {
                    onRegister(pinInput, name)
                } else {
                    if (step == 2) {
                        firstPin = pinInput
                        pinInput = ""
                        step = 3
                    } else if (step == 3) {
                        if (pinInput == firstPin) {
                            onRegister(pinInput, name)
                        } else {
                            scope.launch {
                                isLocalError = true
                                shakeOffset.triggerShakeAnimation()
                                delay(600.milliseconds)
                                firstPin = null
                                pinInput = ""
                                step = 2
                                isLocalError = false
                            }
                        }
                    }
                }
            }
        }
    }

    fun removeDigit() {
        if (pinInput.isNotEmpty() && !isLocalError && !isError && !isRecoveringFromError) pinInput = pinInput.dropLast(1)
    }

    LaunchedEffect(step) { if (step >= 2) mainFocusRequester.requestFocus() }

    with(sharedTransitionScope) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .sharedBounds(
                    rememberSharedContentState(key = containerKey),
                    animatedVisibilityScope = animatedVisibilityScope,
                    boundsTransform = { _, _ -> tween(500) }
                )
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp) {
                        when (event.key) {
                            Key.Escape -> {
                                if (isLoading) onCancel() else goBack()
                                true
                            }
                            Key.Enter -> {
                                if (isSuccess) {
                                    onComplete(wantsBiometrics)
                                    true
                                } else if (step == 1 && name.isNotBlank()) {
                                    handleNameSubmit()
                                    true
                                } else false
                            }
                            else -> false
                        }
                    } else false
                }
                .pinInputHandler(
                    focusRequester = mainFocusRequester,
                    enabled = step >= 2 && !isLoading && !isRecoveringFromError,
                    onDigit = { handlePinInput(it) },
                    onDelete = { removeDigit() },
                    onEscape = { goBack() }
                )
        ) {
            AnimatedContent(
                targetState = if (isSuccess) 4 else step,
                label = "setup_step",
                transitionSpec = {
                    fadeIn(animationSpec = tween(400)) togetherWith fadeOut(animationSpec = tween(400))
                }
            ) { currentStep ->
                SplitLayout(
                    cardContent = {
                        BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            val metrics = rememberCardMetrics(maxWidth, maxHeight)
                            val heightBasedWidth = (maxHeight - 40.dp).coerceAtLeast(0.dp) * 1.586f
                            val targetWidth = min(maxWidth * 1.1f, heightBasedWidth)

                            Box(
                                modifier = Modifier
                                    .width(targetWidth)
                                    .sharedBounds(
                                        sharedContentState = rememberSharedContentState(key = cardKey),
                                        animatedVisibilityScope = animatedVisibilityScope,
                                        boundsTransform = { _, _ -> tween(500) },
                                        renderInOverlayDuringTransition = false
                                    )
                            ) {
                                val showOverlay = currentStep >= 2
                                WalletCard(
                                    account = previewAccount,
                                    modifier = Modifier.fillMaxWidth(),
                                    isOnline = isCardConnected,
                                    showFullId = false,
                                    overlayContent = if (showOverlay) {
                                        {
                                            if (currentStep == 4) {
                                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                                    Icon(
                                                        imageVector = Icons.Default.CheckCircle,
                                                        contentDescription = "Success",
                                                        tint = Color(0xFF4CAF50),
                                                        modifier = Modifier.size(metrics.actualHeight * 0.5f)
                                                    )
                                                }
                                            } else {
                                                val overlayTitle = when {
                                                    isError -> "WRONG PIN"
                                                    isLocalError -> "NO MATCH"
                                                    currentStep == 2 -> if (cardHasPin) "ENTER PIN" else "CREATE PIN"
                                                    else -> "CONFIRM PIN"
                                                }
                                                PinOverlay(
                                                    title = overlayTitle,
                                                    pinLength = pinInput.length,
                                                    isError = isLocalError || isError,
                                                    shakeOffset = shakeOffset.value,
                                                    metrics = metrics
                                                )
                                            }
                                        }
                                    } else null
                                )
                            }
                        }
                    },
                    inputContent = {
                        BoxWithConstraints(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            val metrics = rememberNumPadMetrics(maxWidth, maxHeight)

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.widthIn(max = 400.dp)
                            ) {
                                when (currentStep) {
                                    1 -> NameInputSection(name, { name = it }, handleNameSubmit, metrics.buttonSize)
                                    4 -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(80.dp), tint = Color(0xFF4CAF50))
                                        Spacer(Modifier.height(16.dp))
                                        Text("Setup successful!", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                                        Spacer(Modifier.height(32.dp))

                                        var wantsBiometrics by remember { mutableStateOf(secureStorage.isBiometricSupported) }

                                        if (secureStorage.isBiometricSupported) {
                                            dole.ui.components.AppSwitchButton(
                                                title = "Use Biometrics",
                                                checked = wantsBiometrics,
                                                onCheckedChange = { wantsBiometrics = it }
                                            )
                                            Spacer(Modifier.height(32.dp))
                                        }

                                        Button(
                                            onClick = { onComplete(wantsBiometrics) },
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                            modifier = Modifier.height(50.dp).width(200.dp)
                                        ) {
                                            Text("Open Dashboard", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    else -> ResizableNumPad(
                                        buttonSize = metrics.buttonSize,
                                        textSize = metrics.textSize,
                                        onDigit = { handlePinInput(it) },
                                        onDelete = { removeDigit() }
                                    )
                                }
                            }
                        }
                    },
                    bottomContent = {
                        val buttonText = if (isLoading) "Cancel" else if (isSuccess) "Close" else "Cancel"
                        val buttonAction = if (isLoading) onCancel else { { goBack() } }
                        TextButton(onClick = buttonAction, modifier = Modifier.height(48.dp)) {
                            Text(buttonText, color = if (isLoading) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        }
                    }
                )
            }

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.8f)).zIndex(100f).clickable(enabled = false) {},
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(60.dp), strokeWidth = 4.dp)
                            Spacer(Modifier.height(32.dp))
                            Text("Please hold card to your device", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                        }
                        Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(100.dp).padding(bottom = 32.dp), contentAlignment = Alignment.Center) {
                            TextButton(onClick = onCancel, modifier = Modifier.height(48.dp)) {
                                Text("Cancel", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}