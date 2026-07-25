package dole.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.mohamedrejeb.calf.ui.utils.LocalBackdrop
import dole.viewmodel.WalletApp
import dole.viewmodel.WalletViewModel
import platform.UIKit.UIViewController

fun MainViewController(viewModel: WalletViewModel): UIViewController = ComposeUIViewController {
    val rootBackdrop = rememberLayerBackdrop()
    CompositionLocalProvider(LocalBackdrop provides rootBackdrop) {
        Box(Modifier.fillMaxSize().layerBackdrop(rootBackdrop)) {
            WalletApp(viewModel)
        }
    }
}
