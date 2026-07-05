@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package dole.ui

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.russhwolf.settings.NSUserDefaultsSettings
import dole.card.SmartCard
import dole.core.CoreWrapper
import dole.data.AccountRepositoryImpl
import dole.data.AccountStorage
import dole.utils.IosSecureStorage
import dole.viewmodel.WalletApp
import dole.viewmodel.WalletViewModel
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIViewController

fun MainViewController(card: SmartCard): UIViewController = ComposeUIViewController {

    val documentDirectory = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null
    )

    val storagePath = documentDirectory?.path ?: ""

    val secureStorage = remember { IosSecureStorage() }
    val accountStorage = remember {
        AccountStorage(NSUserDefaultsSettings(NSUserDefaults.standardUserDefaults))
    }
    val accountRepo = remember {
        AccountRepositoryImpl(secureStorage, accountStorage)
    }
    val viewModel = remember(storagePath) {
        WalletViewModel(accountRepo, accountStorage, card, storagePath)
    }

    DisposableEffect(storagePath) {
        CoreWrapper.startGlobalSync(storagePath)
        CoreWrapper.startBleAdvertising(storagePath)

        onDispose {
            CoreWrapper.stopBleAdvertising()
            CoreWrapper.stopGlobalSync()
        }
    }
    
    DisposableEffect(viewModel) {
        val center = NSNotificationCenter.defaultCenter
        val backgroundObserver = center.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) { _ -> viewModel.onAppBackground() }
        val foregroundObserver = center.addObserverForName(
            name = UIApplicationWillEnterForegroundNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) { _ -> viewModel.onAppForeground() }

        onDispose {
            center.removeObserver(backgroundObserver)
            center.removeObserver(foregroundObserver)
        }
    }

    WalletApp(viewModel)
}
