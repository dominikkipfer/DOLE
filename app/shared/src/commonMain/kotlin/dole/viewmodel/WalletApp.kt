package dole.viewmodel

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.zIndex
import dole.data.models.StoredAccount
import dole.ui.components.LocalCardPulse
import dole.ui.components.NewCardOverlay
import dole.ui.screens.AuthScreen
import dole.ui.screens.DashboardScreen
import dole.ui.screens.DeveloperScreen
import dole.ui.screens.HomeScreen
import dole.ui.screens.SettingsScreen
import dole.ui.screens.SetupScreen
import dole.ui.theme.DoleTheme
import kotlinx.coroutines.launch

@Composable
fun WalletApp(viewModel: WalletViewModel) {
    DoleTheme(themeMode = viewModel.developer.themeMode) {
        val errorMessage = viewModel.errorMessage
        val userMessage = viewModel.userMessage

        val isPinError = errorMessage?.contains("PIN", ignoreCase = true) == true ||
                errorMessage?.contains("Verification failed", ignoreCase = true) == true ||
                errorMessage?.contains("remaining", ignoreCase = true) == true

        val shouldShowSnackbar = errorMessage != null && (
                errorMessage.contains("remaining", ignoreCase = true) ||
                        errorMessage.contains("bricked", ignoreCase = true) ||
                        (!errorMessage.contains("Invalid PIN", ignoreCase = true) &&
                                !errorMessage.equals("PIN verification failed", ignoreCase = true))
                )

        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val focusManager = LocalFocusManager.current

        LaunchedEffect(errorMessage) {
            if (errorMessage != null && shouldShowSnackbar) {
                scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(message = errorMessage)
                }
            }
        }

        LaunchedEffect(userMessage) {
            if (userMessage != null) {
                scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(message = userMessage)
                    viewModel.dismissUserMessage()
                }
            }
        }

        var cachedLoginAccount by remember { mutableStateOf<StoredAccount?>(null) }
        if (viewModel.detectedAccount != null) cachedLoginAccount = viewModel.detectedAccount

        val liveConnectedAccount = viewModel.physicallyConnectedCardAccount?.takeIf { viewModel.isCardConnected }

        val infiniteTransition = rememberInfiniteTransition(label = "global_pulse")
        val globalPulse by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(4000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "pulse_value"
        )

        var manuallyDismissed by remember { mutableStateOf(false) }

        LaunchedEffect(viewModel.isNewCardDetected, viewModel.isCardConnected) {
            if (viewModel.isCardConnected || !viewModel.isNewCardDetected) manuallyDismissed = false
        }

        val showNewCardOverlay = viewModel.isNewCardDetected && !manuallyDismissed &&
                viewModel.currentScreen != AppScreenState.SETUP && viewModel.currentScreen != AppScreenState.LOGIN

        SharedTransitionLayout {
            CompositionLocalProvider(LocalCardPulse provides globalPulse) {
                Scaffold(
                    contentWindowInsets = WindowInsets(0),
                    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                    content = { contentPadding ->
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(contentPadding)
                                .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
                        ) {
                            AnimatedContent(
                                targetState = viewModel.currentScreen,
                                label = "ScreenTransition",
                                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                                transitionSpec = {
                                    fadeIn(animationSpec = tween(400)) togetherWith fadeOut(animationSpec = tween(400))
                                }
                            ) { targetScreen ->
                                val animatedVisibilityScope = this
                                when (targetScreen) {
                                    AppScreenState.HOME -> HomeScreen(
                                        accounts = viewModel.availableAccounts,
                                        onAccountClick = {
                                            viewModel.selectAccountToLogin(
                                                it
                                            )
                                        },
                                        physicallyConnectedAccount = liveConnectedAccount,
                                        isOverlayVisible = showNewCardOverlay,
                                        initialSelectedAccountId = cachedLoginAccount?.id,
                                        globalHistory = viewModel.globalHistory,
                                        networkAccounts = viewModel.networkAccounts,
                                        nameResolver = viewModel::getPeerName,
                                        onNotify = { viewModel.showUserMessage(it) },
                                        sharedTransitionScope = this@SharedTransitionLayout,
                                        animatedVisibilityScope = animatedVisibilityScope,
                                        onLogoTap = { viewModel.developer.registerLogoTap() },
                                        developerPane = if (viewModel.developer.isEnabled) {
                                            { DeveloperScreen(viewModel) }
                                        } else {
                                            null
                                        }
                                    )
                                    AppScreenState.LOGIN -> {
                                        val accountToShow = cachedLoginAccount
                                        if (accountToShow != null) {
                                            AuthScreen(
                                                account = accountToShow,
                                                physicallyConnectedAccount = liveConnectedAccount,
                                                isBiometricsEnabled = viewModel.isBiometricsEnabled(accountToShow.id),
                                                isError = isPinError,
                                                onErrorShown = { viewModel.dismissError() },
                                                onLogin = { viewModel.attemptLogin(it) },
                                                onCancel = { viewModel.cancelAuth() },
                                                sharedTransitionScope = this@SharedTransitionLayout,
                                                animatedVisibilityScope = animatedVisibilityScope
                                            )
                                        } else {
                                            Box(Modifier.fillMaxSize())
                                        }
                                    }
                                    AppScreenState.SETUP -> {
                                        val targetCardId = viewModel.setupTargetCardId ?: "NULL"
                                        val isTargetCardConnected = viewModel.isCardConnected &&
                                                viewModel.currentDetectedCardId == targetCardId

                                        SetupScreen(
                                            cardId = targetCardId,
                                            cardHasPin = viewModel.newCardHasPin,
                                            initialName = "",
                                            isSuccess = viewModel.isSetupSuccessful,
                                            isLoading = viewModel.isSetupLoading,
                                            isCardConnected = isTargetCardConnected,
                                            isError = isPinError,
                                            onErrorShown = { viewModel.dismissError() },
                                            onRegister = { pin, name -> viewModel.handleSetupOrImportSubmit(pin, name) },
                                            onCancel = { viewModel.logout() },
                                            onComplete = { wantsBiometrics -> viewModel.completeSetup(wantsBiometrics) },
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            animatedVisibilityScope = animatedVisibilityScope
                                        )
                                    }
                                    AppScreenState.DASHBOARD -> DashboardScreen(
                                        viewModel = viewModel,
                                        sharedTransitionScope = this@SharedTransitionLayout,
                                        animatedVisibilityScope = animatedVisibilityScope
                                    )
                                    AppScreenState.SETTINGS -> SettingsScreen(
                                        viewModel = viewModel,
                                        onBack = { viewModel.currentScreen = AppScreenState.DASHBOARD }
                                    )
                                }
                            }

                            val enterTransition = slideInVertically(initialOffsetY = { it }) + fadeIn()
                            val exitTransition = slideOutVertically(targetOffsetY = { it }) + fadeOut()

                            AnimatedVisibility(
                                visible = showNewCardOverlay,
                                enter = enterTransition,
                                exit = exitTransition,
                                modifier = Modifier.align(Alignment.BottomCenter).zIndex(9999f)
                            ) {
                                NewCardOverlay(
                                    cardId = viewModel.currentDetectedCardId ?: "Unknown",
                                    onConnect = { viewModel.goToSetup() },
                                    onDismiss = { manuallyDismissed = true },
                                    sharedTransitionScope = this@SharedTransitionLayout,
                                    animatedVisibilityScope = this
                                )
                            }
                        }
                    }
                )
            }
        }
    }
}