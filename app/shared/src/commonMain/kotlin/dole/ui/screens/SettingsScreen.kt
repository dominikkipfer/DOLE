package dole.ui.screens

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.mohamedrejeb.calf.ui.button.AdaptiveButton
import com.mohamedrejeb.calf.ui.button.LiquidGlassButtonColors
import com.mohamedrejeb.calf.ui.dialog.AdaptiveAlertDialog
import com.mohamedrejeb.calf.ui.dialog.uikit.AlertDialogIosActionStyle
import com.mohamedrejeb.calf.ui.progress.AdaptiveCircularProgressIndicator
import dole.data.models.StoredAccount
import dole.ui.components.AppBackHandler
import dole.ui.components.AppButton
import dole.ui.components.AppSwitchButton
import dole.ui.components.AppTextButton
import dole.ui.components.NameInputSection
import dole.ui.components.PinOverlay
import dole.ui.components.ResizableNumPad
import dole.ui.components.WalletCard
import dole.ui.components.triggerShakeAnimation
import dole.ui.layouts.SplitLayout
import dole.ui.metrics.rememberCardMetrics
import dole.ui.metrics.rememberNumPadMetrics
import dole.ui.modifiers.pinInputHandler
import dole.utils.ScreenCaptureProtection
import dole.utils.rememberSecureStorage
import dole.viewmodel.WalletViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

private enum class SettingsStep { MENU, NAME, PIN_ENTER, PIN_CONFIRM, SUCCESS }

@Composable
fun SettingsScreen(viewModel: WalletViewModel, onBack: () -> Unit) {
    var step by remember { mutableStateOf(SettingsStep.MENU) }

    var nameInput by remember { mutableStateOf(viewModel.currentName) }
    var pinInput by remember { mutableStateOf("") }
    var firstPin by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val secureStorage = rememberSecureStorage()

    var isError by remember { mutableStateOf(false) }
    val shakeOffset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    val isLoading = viewModel.isSettingsLoading

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
            delay(600.milliseconds)
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

    AppBackHandler {
        if (isLoading) return@AppBackHandler
        when (step) {
            SettingsStep.MENU -> onBack()
            SettingsStep.SUCCESS -> Unit
            else -> resetToMenu()
        }
    }

    LaunchedEffect(step) {
        if (step != SettingsStep.NAME) focusRequester.requestFocus()
    }

    val previewAccount = remember(nameInput, viewModel.currentId) {
        StoredAccount(viewModel.currentId ?: "", nameInput.ifEmpty { viewModel.currentName }, "")
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pinInputHandler(
                focusRequester = focusRequester,
                enabled = !isLoading && (step == SettingsStep.PIN_ENTER || step == SettingsStep.PIN_CONFIRM),
                onDigit = { handleDigit(it) },
                onDelete = { if (pinInput.isNotEmpty()) pinInput = pinInput.dropLast(1) }
            )
    ) {
        AnimatedContent(
            targetState = step,
            label = "settings_step",
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                fadeIn(tween(400)) togetherWith fadeOut(tween(400))
            }
        ) { currentStep ->
            when (currentStep) {
                SettingsStep.MENU -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .padding(top = 48.dp, bottom = 8.dp)
                    )

                    BoxWithConstraints(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)
                    ) {
                        val numPadMetrics = rememberNumPadMetrics(maxWidth, maxHeight)

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(numPadMetrics.buttonSize * 0.25f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (secureStorage.isBiometricSupported) {
                                var isBioEnabled by remember { mutableStateOf(viewModel.isBiometricsEnabled(viewModel.currentId ?: "")) }

                                AppSwitchButton(
                                    title = "Use Biometrics",
                                    checked = isBioEnabled,
                                    onCheckedChange = { isChecked ->
                                        isBioEnabled = isChecked
                                        viewModel.setBiometricsEnabled(viewModel.currentId ?: "", isChecked)
                                    }
                                )
                            }

                            if (ScreenCaptureProtection.isSupported) {
                                var isCaptureBlocked by remember { mutableStateOf(viewModel.isScreenCaptureBlocked()) }

                                AppSwitchButton(
                                    title = "Block Screenshots",
                                    checked = isCaptureBlocked,
                                    onCheckedChange = { isChecked ->
                                        isCaptureBlocked = isChecked
                                        viewModel.setScreenCaptureBlocked(isChecked)
                                    }
                                )
                            }

                            Spacer(Modifier.height(16.dp))

                            SettingsMenuButton("Change Name") { step = SettingsStep.NAME }
                            SettingsMenuButton("Change PIN") { step = SettingsStep.PIN_ENTER }

                            Spacer(Modifier.height(16.dp))

                            SettingsMenuButton("Delete account on device", color = MaterialTheme.colorScheme.error) {
                                showDeleteConfirm = true
                            }
                        }
                    }

                    Box(
                        modifier = Modifier.fillMaxWidth().height(60.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        AppTextButton(text = "Close", onClick = onBack, modifier = Modifier.height(48.dp))
                    }
                }
                else -> SplitLayout(
                    cardContent = {
                        BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            val metrics = rememberCardMetrics(maxWidth, maxHeight)
                            val heightBasedWidth = (maxHeight - 40.dp).coerceAtLeast(0.dp) * 1.586f
                            val targetWidth = min(maxWidth * 1.1f, heightBasedWidth)

                            val showOverlay = currentStep != SettingsStep.NAME

                            Box(modifier = Modifier.width(targetWidth)) {
                                WalletCard(
                                    account = previewAccount,
                                    modifier = Modifier.fillMaxWidth(),
                                    isOnline = viewModel.isCardConnected,
                                    showFullId = false,
                                    overlayContent = if (showOverlay) {
                                        {
                                            if (currentStep == SettingsStep.SUCCESS) {
                                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                                    Icon(
                                                        imageVector = Icons.Default.CheckCircle,
                                                        contentDescription = "Success",
                                                        tint = Color(0xFF4CAF50),
                                                        modifier = Modifier.size(metrics.actualHeight * 0.5f)
                                                    )
                                                }
                                            } else {
                                                PinOverlay(
                                                    title = if (currentStep == SettingsStep.PIN_ENTER) "NEW PIN" else "CONFIRM",
                                                    pinLength = pinInput.length,
                                                    isError = isError,
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
                                    SettingsStep.NAME -> NameInputSection(nameInput, { nameInput = it }, { handleNameSubmit() }, metrics.buttonSize)
                                    SettingsStep.SUCCESS -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Success!", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                                        Spacer(Modifier.height(24.dp))
                                        AppButton(
                                            text = "Back to Dashboard",
                                            onClick = onBack,
                                            modifier = Modifier.height(50.dp).width(200.dp)
                                        )
                                    }
                                    else -> ResizableNumPad(
                                        buttonSize = metrics.buttonSize,
                                        textSize = metrics.textSize,
                                        onDigit = { handleDigit(it) },
                                        onDelete = { if (pinInput.isNotEmpty()) pinInput = pinInput.dropLast(1) }
                                    )
                                }
                            }
                        }
                    },
                    bottomContent = {
                        if (currentStep != SettingsStep.SUCCESS && !isLoading) {
                            AppTextButton(
                                text = "Back",
                                onClick = { resetToMenu() },
                                modifier = Modifier.height(48.dp)
                            )
                        }
                    }
                )
            }
        }

        if (showDeleteConfirm) {
            AdaptiveAlertDialog(
                onConfirm = {
                    showDeleteConfirm = false
                    viewModel.deleteCurrentAccount()
                },
                onDismiss = { showDeleteConfirm = false },
                confirmText = "Delete",
                dismissText = "Cancel",
                title = "Delete Account",
                text = "Remove this account from the device? The card itself is not affected.",
                iosConfirmButtonStyle = AlertDialogIosActionStyle.Destructive,
                iosDismissButtonStyle = AlertDialogIosActionStyle.Cancel
            )
        }

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.8f))
                    .zIndex(100f)
                    .clickable(enabled = false) {},
                contentAlignment = Alignment.Center
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AdaptiveCircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(60.dp), strokeWidth = 4.dp)
                        Spacer(Modifier.height(32.dp))
                        Text("Hold card to device...", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                    }

                    Box(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(100.dp).padding(bottom = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        AppTextButton(text = "Cancel", onClick = { resetToMenu() }, modifier = Modifier.height(48.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsMenuButton(text: String, color: Color = MaterialTheme.colorScheme.onBackground, onClick: () -> Unit) {
    AdaptiveButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = color
        ),
        liquidGlassColors = LiquidGlassButtonColors(
            tintColor = Color.Unspecified,
            surfaceColor = Color.Unspecified,
            contentColor = color,
            disabledContentColor = color.copy(alpha = 0.4f)
        )
    ) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}
