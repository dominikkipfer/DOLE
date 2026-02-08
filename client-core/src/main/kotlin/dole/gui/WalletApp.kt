package dole.gui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dole.gui.AppScreenState.DASHBOARD
import dole.gui.AppScreenState.HOME
import dole.gui.AppScreenState.LOGIN
import dole.gui.AppScreenState.SETTINGS
import dole.gui.AppScreenState.SETUP
import dole.wallet.StoredAccount
import kotlinx.coroutines.launch

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun WalletApp(viewModel: WalletViewModel) {
    val errorMessage = viewModel.errorMessage

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

    LaunchedEffect(errorMessage) {
        if (errorMessage != null && shouldShowSnackbar) {
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(message = errorMessage)
            }
        }
    }

    var cachedLoginAccount by remember { mutableStateOf<StoredAccount?>(null) }
    if (viewModel.detectedAccount != null) cachedLoginAccount = viewModel.detectedAccount

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

    LaunchedEffect(viewModel.isNewCardDetected) {
        if (!viewModel.isNewCardDetected) manuallyDismissed = false
    }

    val showNewCardOverlay = viewModel.isNewCardDetected && !manuallyDismissed &&
            viewModel.currentScreen != SETUP && viewModel.currentScreen != LOGIN

    SharedTransitionLayout {
        CompositionLocalProvider(LocalCardPulse provides globalPulse) {
            Scaffold(
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                content = { paddingValues ->
                    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(paddingValues)) {

                        AnimatedContent(
                            targetState = viewModel.currentScreen,
                            label = "ScreenTransition",
                            transitionSpec = {
                                fadeIn(animationSpec = tween(400)) togetherWith fadeOut(animationSpec = tween(400))
                            }
                        ) { targetScreen ->
                            val animatedVisibilityScope = this

                            when (targetScreen) {
                                HOME -> HomeScreen(
                                    accounts = viewModel.availableAccounts,
                                    onAccountClick = { viewModel.selectAccountToLogin(it) },
                                    physicallyConnectedAccount = viewModel.physicallyConnectedCardAccount,
                                    isOverlayVisible = showNewCardOverlay,
                                    initialSelectedAccountId = cachedLoginAccount?.id(),
                                    sharedTransitionScope = this@SharedTransitionLayout,
                                    animatedVisibilityScope = animatedVisibilityScope
                                )
                                LOGIN -> {
                                    val accountToShow = cachedLoginAccount
                                    if (accountToShow != null) {
                                        AuthScreen(
                                            account = accountToShow,
                                            physicallyConnectedAccount = viewModel.physicallyConnectedCardAccount,
                                            isError = isPinError,
                                            onErrorShown = { viewModel.dismissError() },
                                            onLogin = { viewModel.attemptLogin(it) },
                                            onCancel = { viewModel.cancelAuth() },
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            animatedVisibilityScope = animatedVisibilityScope
                                        )
                                    } else { Box(Modifier.fillMaxSize()) }
                                }
                                SETUP -> {
                                    val targetCardId = viewModel.setupTargetCardId ?: "NULL"
                                    val isTargetCardConnected = viewModel.currentDetectedCardId != null &&
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
                                        onRegister = { pin, name -> viewModel.registerNewCard(pin, name) },
                                        onCancel = { viewModel.logout() },
                                        onComplete = { viewModel.completeSetup() },
                                        sharedTransitionScope = this@SharedTransitionLayout,
                                        animatedVisibilityScope = animatedVisibilityScope
                                    )
                                }
                                DASHBOARD -> DashboardScreen(
                                    viewModel = viewModel,
                                    sharedTransitionScope = this@SharedTransitionLayout,
                                    animatedVisibilityScope = animatedVisibilityScope
                                )
                                SETTINGS -> SettingsScreen(
                                    viewModel = viewModel,
                                    onBack = { viewModel.currentScreen = DASHBOARD },
                                    sharedTransitionScope = this@SharedTransitionLayout,
                                    animatedVisibilityScope = animatedVisibilityScope
                                )
                            }
                        }

                        val enterTransition = slideInVertically(initialOffsetY = { it }) + fadeIn()
                        val exitTransition = slideOutVertically(targetOffsetY = { it }) + fadeOut()

                        AnimatedVisibility(
                            visible = showNewCardOverlay,
                            enter = enterTransition,
                            exit = exitTransition,
                            modifier = Modifier.zIndex(9999f)
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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun NewCardOverlay(
    cardId: String,
    onConnect: () -> Unit,
    onDismiss: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope
) {
    val containerKey = "setup-container-$cardId"
    val cardKey = "card-$cardId"
    val unifiedSpacing = 24.dp

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        with(sharedTransitionScope) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .heightIn(min = 100.dp)
                    .sharedBounds(
                        rememberSharedContentState(key = containerKey),
                        animatedVisibilityScope = animatedVisibilityScope,
                        boundsTransform = { _, _ -> tween(500) }
                    ),
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                color = Color.White,
                shadowElevation = 32.dp
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(unifiedSpacing),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    OverlayHeader(onDismiss)

                    Spacer(modifier = Modifier.height(unifiedSpacing))

                    Box(modifier = Modifier.weight(1f, fill = false)) {
                        OverlayCardSection(
                            cardId = cardId,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            cardKey = cardKey
                        )
                    }

                    Spacer(modifier = Modifier.height(unifiedSpacing))

                    OverlayButton(onConnect)
                }
            }
        }
    }
}

@Composable
private fun OverlayHeader(onDismiss: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "New Card",
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 32.sp),
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            modifier = Modifier.align(Alignment.Center)
        )

        IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterEnd).size(32.dp)) {
            Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = Color.Black)
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun OverlayCardSection(
    cardId: String,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    cardKey: String
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val parentWidth = maxWidth
        val parentHeight = maxHeight
        val aspectRatio = 1.586f
        val heightBasedWidth = parentHeight * aspectRatio
        val targetWidth = min(parentWidth * 0.9f, heightBasedWidth)

        if (targetWidth > 20.dp) {
            with(sharedTransitionScope) {
                Box(
                    modifier = Modifier.width(targetWidth).sharedBounds(
                        sharedContentState = rememberSharedContentState(key = cardKey),
                        animatedVisibilityScope = animatedVisibilityScope,
                        boundsTransform = { _, _ -> tween(500) }
                    )
                ) {
                    val dummyAccount = remember(cardId) { StoredAccount(cardId, "", "") }
                    WalletCard(
                        account = dummyAccount,
                        isOnline = true,
                        showFullId = false,
                        showInlineLabels = true,
                        modifier = Modifier.fillMaxWidth().aspectRatio(aspectRatio)
                    )
                }
            }
        }
    }
}

@Composable
private fun OverlayButton(onConnect: () -> Unit) {
    Button(
        onClick = onConnect,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().height(56.dp)
    ) {
        Text(text = "Connect", fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}