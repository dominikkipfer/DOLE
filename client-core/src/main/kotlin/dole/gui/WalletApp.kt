package dole.gui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dole.gui.AppScreenState.DASHBOARD
import dole.gui.AppScreenState.HOME
import dole.gui.AppScreenState.LOGIN
import dole.gui.AppScreenState.SETTINGS
import dole.gui.AppScreenState.SETUP
import dole.wallet.StoredAccount

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun WalletApp(viewModel: WalletViewModel) {
    val isAuthError = viewModel.errorMessage?.contains("PIN", ignoreCase = true) == true

    var cachedLoginAccount by remember { mutableStateOf<StoredAccount?>(null) }
    if (viewModel.detectedAccount != null) {
        cachedLoginAccount = viewModel.detectedAccount
    }

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

    SharedTransitionLayout {
        CompositionLocalProvider(LocalCardPulse provides globalPulse) {
            Box(modifier = Modifier.fillMaxSize()) {

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
                            onAddAccount = { viewModel.goToSetup() },
                            physicallyConnectedAccount = viewModel.physicallyConnectedCardAccount,
                            isNewCardDetected = viewModel.isNewCardDetected,
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
                                    isError = isAuthError,
                                    onErrorShown = { viewModel.dismissError() },
                                    onLogin = { viewModel.attemptLogin(it) },
                                    onCancel = { viewModel.cancelAuth() },
                                    sharedTransitionScope = this@SharedTransitionLayout,
                                    animatedVisibilityScope = animatedVisibilityScope
                                )
                            } else { Box(Modifier.fillMaxSize()) }
                        }
                        SETUP -> {
                            val displayId = viewModel.currentDetectedCardId ?: "NULL"
                            SetupScreen(
                                cardId = displayId,
                                cardHasPin = viewModel.newCardHasPin,
                                initialName = "",
                                isSuccess = viewModel.isSetupSuccessful,
                                isLoading = viewModel.isSetupLoading,
                                isCardConnected = viewModel.currentDetectedCardId != null && viewModel.currentDetectedCardId == displayId,
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

                if (viewModel.errorMessage != null && !isAuthError) {
                    SafeOverlay(onDismissRequest = { viewModel.dismissError() }) {
                        Text("Info", style = MaterialTheme.typography.headlineSmall)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(viewModel.errorMessage ?: "")
                        Spacer(modifier = Modifier.height(24.dp))
                        Row(Modifier.fillMaxWidth(), androidx.compose.foundation.layout.Arrangement.End) {
                            TextButton(onClick = { viewModel.dismissError() }) {
                                Text("OK")
                            }
                        }
                    }
                }
            }
        }
    }
}