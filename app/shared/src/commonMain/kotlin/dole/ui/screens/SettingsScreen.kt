package dole.ui.screens
/*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dole.ui.components.WalletCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class SettingsStep { MENU, NAME, PIN_ENTER, PIN_CONFIRM, SUCCESS }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SettingsScreen(
    viewModel: WalletViewModel,
    onBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope
) {
    var step by remember { mutableStateOf(SettingsStep.MENU) }

    var nameInput by remember { mutableStateOf(viewModel.currentName) }
    var pinInput by remember { mutableStateOf("") }
    var firstPin by remember { mutableStateOf<String?>(null) }

    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    var isError by remember { mutableStateOf(false) }
    val shakeOffset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    val isLoading = viewModel.isSettingsLoading

    val previewAccount = remember(nameInput, viewModel.currentId) {
        StoredAccount(viewModel.currentId ?: "", nameInput, "")
    }

    fun resetToMenu() {
        step = SettingsStep.MENU
        pinInput = ""
        firstPin = null
        nameInput = viewModel.currentName
        isError = false
    }

    fun triggerErrorAnimation() {
        scope.launch {
            isError = true
            shakeOffset.triggerShakeAnimation()
            delay(600)
            pinInput = ""
            firstPin = null
            step = SettingsStep.PIN_ENTER
            isError = false
        }
    }

    fun handleDigit(digit: String) {
        if (pinInput.length < 4 && !isError && !isLoading) {
            pinInput += digit
            if (pinInput.length == 4) {
                if (step == SettingsStep.PIN_ENTER) {
                    firstPin = pinInput
                    pinInput = ""
                    step = SettingsStep.PIN_CONFIRM
                } else if (step == SettingsStep.PIN_CONFIRM) {
                    if (pinInput == firstPin) {
                        viewModel.changeCardPin(
                            newPin = pinInput,
                            onSuccess = { step = SettingsStep.SUCCESS },
                            onError = { triggerErrorAnimation() }
                        )
                    } else {
                        triggerErrorAnimation()
                    }
                }
            }
        }
    }

    fun handleNameSubmit() {
        if (nameInput.isNotBlank()) {
            viewModel.updateAccountName(nameInput)
            step = SettingsStep.SUCCESS
        }
    }

    LaunchedEffect(step) {
        if (step != SettingsStep.NAME) focusRequester.requestFocus()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key == Key.Escape) {
                    if (!isLoading) {
                        if (step == SettingsStep.MENU) onBack() else if (step != SettingsStep.SUCCESS) resetToMenu()
                    }
                    true
                } else false
            }
            .pinInputHandler(
                focusRequester = focusRequester,
                enabled = !isLoading && (step == SettingsStep.PIN_ENTER || step == SettingsStep.PIN_CONFIRM),
                onDigit = { handleDigit(it) },
                onDelete = { if (pinInput.isNotEmpty()) pinInput = pinInput.dropLast(1) },
                onEscape = { }
            )
    ) {
        AuthSplitLayout(
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
                                    sharedContentState = rememberSharedContentState(key = "card-${viewModel.currentId}"),
                                    animatedVisibilityScope = animatedVisibilityScope,
                                    boundsTransform = { _, _ -> tween(500) },
                                    renderInOverlayDuringTransition = false
                                )
                        ) {
                            val title = when(step) {
                                SettingsStep.MENU -> "SETTINGS"
                                SettingsStep.NAME -> "NEW NAME"
                                SettingsStep.PIN_ENTER -> "NEW PIN"
                                SettingsStep.PIN_CONFIRM -> "CONFIRM"
                                SettingsStep.SUCCESS -> "UPDATED"
                            }

                            val accountToShow = if(step == SettingsStep.NAME) previewAccount else StoredAccount(viewModel.currentId?:"", viewModel.currentName, "")

                            val showOverlay = step == SettingsStep.PIN_ENTER || step == SettingsStep.PIN_CONFIRM || step == SettingsStep.SUCCESS

                            key(viewModel.isCardConnected) {
                                WalletCard(
                                    account = accountToShow,
                                    modifier = Modifier.fillMaxWidth(),
                                    isOnline = viewModel.isCardConnected,
                                    showFullId = true,
                                    onIdClick = {
                                        viewModel.currentId?.let { id ->
                                            copyToClipboard(
                                                clipboardManager = clipboardManager,
                                                text = id,
                                                onSuccess = {
                                                    viewModel.showUserMessage(
                                                        "ID copied to clipboard"
                                                    )
                                                }
                                            )
                                        }
                                    },
                                    overlayContent = if (showOverlay) {
                                        {
                                            if (step == SettingsStep.SUCCESS) {
                                                Box(
                                                    contentAlignment = Alignment.Center,
                                                    modifier = Modifier.fillMaxSize()
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.CheckCircle,
                                                        contentDescription = "Success",
                                                        tint = Color(
                                                            0xFF4CAF50
                                                        ),
                                                        modifier = Modifier.size(
                                                            metrics.actualHeight * 0.5f
                                                        )
                                                    )
                                                }
                                            } else {
                                                PinOverlay(
                                                    title,
                                                    pinInput.length,
                                                    isError,
                                                    shakeOffset.value,
                                                    metrics
                                                )
                                            }
                                        }
                                    } else null
                                )
                            }
                        }
                    }
                }
            },
            inputContent = {
                BoxWithConstraints(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    val numPadMetrics = rememberNumPadMetrics(maxWidth, maxHeight)

                    AnimatedContent(
                        targetState = step,
                        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) }
                    ) { currentStep ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(numPadMetrics.buttonSize * 0.25f),
                            modifier = Modifier.widthIn(max = 400.dp)
                        ) {
                            when (currentStep) {
                                SettingsStep.MENU -> {
                                    SettingsMenuButton("Change Name") { step = SettingsStep.NAME }
                                    SettingsMenuButton("Change PIN") { step = SettingsStep.PIN_ENTER }

                                    Spacer(Modifier.height(16.dp))

                                    SettingsMenuButton("Delete account on device", color = Color.Red) {
                                        viewModel.deleteCurrentAccount()
                                    }
                                }
                                SettingsStep.NAME -> {
                                    NameInputSection(nameInput, { nameInput = it }, { handleNameSubmit() }, numPadMetrics.buttonSize)
                                }
                                SettingsStep.PIN_ENTER, SettingsStep.PIN_CONFIRM -> {
                                    ResizableNumPad(
                                        buttonSize = numPadMetrics.buttonSize,
                                        textSize = numPadMetrics.textSize,
                                        onDigit = { handleDigit(it) },
                                        onDelete = { if (pinInput.isNotEmpty()) pinInput = pinInput.dropLast(1) }
                                    )
                                }
                                SettingsStep.SUCCESS -> {
                                    Text("Success!", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(16.dp))
                                    Button(
                                        onClick = onBack,
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
                                        modifier = Modifier.height(50.dp).width(200.dp)
                                    ) {
                                        Text("Back to Dashboard")
                                    }
                                }
                            }
                        }
                    }
                }
            },
            bottomContent = {
                if (step != SettingsStep.SUCCESS && !isLoading) {
                    val buttonText = if (step == SettingsStep.MENU) "Close" else "Back"
                    val buttonAction = if (step == SettingsStep.MENU) onBack else { { resetToMenu() } }

                    TextButton(onClick = buttonAction, modifier = Modifier.height(48.dp)) {
                        Text(
                            buttonText,
                            color = Color.Red,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp
                        )
                    }
                }
            }
        )

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.8f))
                    .zIndex(100f)
                    .clickable(enabled = false) {},
                contentAlignment = Alignment.Center
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(60.dp), strokeWidth = 4.dp)
                        Spacer(Modifier.height(32.dp))
                        Text("Hold card to device...", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
                    }

                    Box(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(60.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        TextButton(
                            onClick = {
                                viewModel.cancelSettingsOperation()
                                resetToMenu()
                            },
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("Cancel", color = Color.Red, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsMenuButton(
    text: String,
    color: Color = Color.Black,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(60.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}
 */