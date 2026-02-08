package dole.gui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dole.wallet.StoredAccount
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SetupScreen(
    cardId: String,
    cardHasPin: Boolean,
    initialName: String = "",
    isLoading: Boolean,
    isSuccess: Boolean,
    isCardConnected: Boolean,
    onRegister: (String, String) -> Unit,
    onCancel: () -> Unit,
    onComplete: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope
) {
    var cachedCardId by remember { mutableStateOf(cardId) }
    LaunchedEffect(cardId) { if (cardId != "NULL" && cardId.isNotBlank()) cachedCardId = cardId }

    var name by remember { mutableStateOf(initialName) }
    var pinInput by remember { mutableStateOf("") }
    var firstPin by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableIntStateOf(1) }

    val scope = rememberCoroutineScope()
    var isError by remember { mutableStateOf(false) }
    val shakeOffset = remember { Animatable(0f) }
    val mainFocusRequester = remember { FocusRequester() }

    val previewAccount = remember(name, cachedCardId) {
        StoredAccount(cachedCardId, name.ifEmpty { "YOUR NAME" }, "")
    }

    fun goBack() {
        if (isSuccess) onCancel()
        else if (step > 1) { step -= 1; pinInput = ""; firstPin = null }
        else onCancel()
    }

    val handleNameSubmit = { step = 2 }

    fun handlePinInput(digit: String) {
        if (isError || isSuccess || isLoading) return

        if (pinInput.length < 4) {
            pinInput += digit
            if (pinInput.length == 4) {
                if (cardHasPin) {
                    onRegister(pinInput, name)
                } else {
                    if (step == 2) {
                        firstPin = pinInput; pinInput = ""; step = 3
                    } else if (step == 3) {
                        if (pinInput == firstPin) {
                            onRegister(pinInput, name)
                        } else {
                            scope.launch {
                                isError = true
                                shakeOffset.triggerShakeAnimation()
                                delay(600)
                                firstPin = null
                                pinInput = ""
                                step = 2
                                isError = false
                            }
                        }
                    }
                }
            }
        }
    }

    fun removeDigit() {
        if (pinInput.isNotEmpty() && !isError) pinInput = pinInput.dropLast(1)
    }

    LaunchedEffect(step) { if (step >= 2) mainFocusRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pinInputHandler(
                focusRequester = mainFocusRequester,
                enabled = step >= 2 && !isLoading,
                onDigit = { handlePinInput(it) },
                onDelete = { removeDigit() },
                onEscape = { if (!isLoading) goBack() }
            )
    ) {
        AnimatedContent(
            targetState = if (isSuccess) 4 else step,
            label = "setup_step",
            transitionSpec = {
                fadeIn(animationSpec = tween(400)) togetherWith fadeOut(animationSpec = tween(400))
            }
        ) { currentStep ->
            AuthSplitLayout(
                cardContent = {
                    with(sharedTransitionScope) {
                        BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            val metrics = rememberCardMetrics(maxWidth, maxHeight)

                            val heightBasedWidth = (maxHeight - 40.dp).coerceAtLeast(0.dp) * 1.586f
                            val targetWidth = min(maxWidth * 1.1f, heightBasedWidth)

                            Box(
                                modifier = Modifier
                                    .width(targetWidth)
                                    .sharedBounds(
                                        sharedContentState = rememberSharedContentState(key = "card-${cachedCardId}"),
                                        animatedVisibilityScope = animatedVisibilityScope,
                                        boundsTransform = { _, _ -> tween(500) }
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
                                                val title = when {
                                                    isError -> "NO MATCH"
                                                    currentStep == 2 -> if (cardHasPin) "ENTER PIN" else "CREATE PIN"
                                                    else -> "CONFIRM PIN"
                                                }
                                                PinOverlay(title, pinInput.length, isError, shakeOffset.value, metrics)
                                            }
                                        }
                                    } else null
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
                            when (currentStep) {
                                1 -> NameInputSection(name, { name = it }, handleNameSubmit, metrics.buttonSize)
                                4 -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Spacer(Modifier.height(16.dp))
                                    Text("Setup successful!", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(32.dp))
                                    Button(
                                        onClick = onComplete,
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
                                        modifier = Modifier.height(50.dp).width(200.dp)
                                    ) {
                                        Text("Open Dashboard", fontWeight = FontWeight.Bold)
                                    }
                                }
                                else -> ResizableNumPad(metrics.buttonSize, metrics.textSize, {handlePinInput(it) }, { removeDigit() })
                            }
                        }
                    }
                },
                bottomContent = {
                    val buttonText = if (isLoading) "Cancel" else if (isSuccess) "Close" else "Cancel"
                    val buttonAction = if (isLoading) onCancel else { { goBack() } }

                    TextButton(onClick = buttonAction, modifier = Modifier.height(48.dp)) {
                        Text(
                            text = buttonText,
                            color = if (isLoading) Color.White.copy(alpha = 0.8f) else Color.Red,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp
                        )
                    }
                }
            )
        }

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)).zIndex(100f).clickable(enabled = false) {},
                contentAlignment = Alignment.Center
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(60.dp), strokeWidth = 4.dp)
                        Spacer(Modifier.height(32.dp))
                        Text("Please hold card to your device", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
                    }

                    Box(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(60.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        TextButton(onClick = onCancel, modifier = Modifier.height(48.dp)) {
                            Text("Cancel", color = Color.Red, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        }
                    }
                }
            }
        }
    }
}