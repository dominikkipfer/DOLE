package dole.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import dole.ui.components.LocalCardPulse
import dole.ui.components.NewCardOverlay
import dole.ui.theme.DoleTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun WalletApp(viewModel: WalletViewModel) {
    DoleTheme {
        val errorMessage = viewModel.errorMessage
        val userMessage = viewModel.userMessage

        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()

        LaunchedEffect(errorMessage) {
            if (errorMessage != null) {
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

        val showNewCardOverlay = viewModel.isNewCardDetected && !manuallyDismissed

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
                                    AppScreenState.DASHBOARD -> DashboardScreen(
                                        viewModel = viewModel,
                                        sharedTransitionScope = this@SharedTransitionLayout,
                                        animatedVisibilityScope = animatedVisibilityScope
                                    )
                                    else -> Box(Modifier.fillMaxSize())
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
}