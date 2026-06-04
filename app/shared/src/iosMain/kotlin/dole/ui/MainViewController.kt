@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package dole.ui

import androidx.compose.ui.window.ComposeUIViewController
import dole.ui.screens.WalletApp
import dole.ui.screens.WalletViewModel
import platform.UIKit.UIViewController
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

fun MainViewController(): UIViewController = ComposeUIViewController {
    
    val documentDirectory = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null
    )
    
    val storagePath = documentDirectory?.path ?: ""

    val viewModel = WalletViewModel(storagePath = storagePath)
    WalletApp(viewModel)
}