package dole

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.window.application
import org.jetbrains.compose.resources.decodeToImageBitmap
import com.russhwolf.settings.PreferencesSettings
import dole.card.PCSmartCard
import dole.data.AccountPreferences
import dole.data.AccountRegistry
import dole.data.CardSyncState
import dole.utils.DesktopSecureStorage
import dole.utils.ScreenCaptureProtection
import dole.viewmodel.WalletApp
import dole.viewmodel.WalletViewModel
import java.io.File
import java.util.prefs.Preferences

fun main() = application {
    val userHome = System.getProperty("user.home")
    val doleDir = File(userHome, ".dole")
    if (!doleDir.exists()) doleDir.mkdirs()

    val prefs = Preferences.userRoot().node("dole_settings")
    val settings = PreferencesSettings(prefs)

    val icon = remember {
        BitmapPainter(this::class.java.getResourceAsStream("/dole.png")!!.readAllBytes().decodeToImageBitmap())
    }

    val secureStorage = remember { DesktopSecureStorage() }
    val accountPreferences = remember { AccountPreferences(settings) }
    val cardSyncState = remember { CardSyncState(settings) }
    val accounts = remember { AccountRegistry(settings, secureStorage, accountPreferences, cardSyncState) }
    val card = remember { PCSmartCard() }

    val viewModel = remember {
        WalletViewModel(accounts, accountPreferences, cardSyncState, settings, card, doleDir.absolutePath).also {
            it.onNetworkPermissionsGranted()
        }
    }

    Window(
        onCloseRequest = {
            viewModel.stopNetworkServices()
            exitApplication()
        },
        title = "DOLE",
        icon = icon
    ) {
        LaunchedEffect(Unit) { ScreenCaptureProtection.bind(window) }

        WalletApp(viewModel)
    }
}