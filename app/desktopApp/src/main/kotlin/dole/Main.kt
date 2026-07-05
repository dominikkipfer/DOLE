package dole

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.russhwolf.settings.PreferencesSettings
import dole.card.PCSmartCard
import dole.core.CoreWrapper
import dole.data.AccountRepository
import dole.data.AccountStorage
import dole.utils.DesktopSecureStorage
import dole.utils.ScreenCaptureProtection
import dole.viewmodel.WalletApp
import dole.viewmodel.WalletViewModel
import org.jetbrains.compose.resources.decodeToSvgPainter
import java.io.File
import java.util.prefs.Preferences

fun main() = application {
    val userHome = System.getProperty("user.home")
    val doleDir = File(userHome, ".dole")
    if (!doleDir.exists()) doleDir.mkdirs()

    val prefs = Preferences.userRoot().node("dole_settings")
    val settings = PreferencesSettings(prefs)
    val accountStorage = AccountStorage(settings)

    val icon = remember {
        this::class.java.getResourceAsStream("/DOLE.svg")!!.readAllBytes().decodeToSvgPainter(density = Density(1f))
    }

    remember(doleDir.absolutePath) {
        CoreWrapper.startGlobalSync(doleDir.absolutePath)
        CoreWrapper.startBleAdvertising(doleDir.absolutePath)
        true
    }

    Window(
        onCloseRequest = {
            CoreWrapper.stopBleAdvertising()
            CoreWrapper.stopGlobalSync()
            exitApplication()
        },
        title = "DOLE",
        icon = icon
    ) {
        LaunchedEffect(Unit) { ScreenCaptureProtection.bind(window) }

        val secureStorage = remember { DesktopSecureStorage() }

        val accountRepo = remember { AccountRepository(secureStorage, accountStorage) }

        val card = remember {
            PCSmartCard()
        }

        val viewModel = remember {
            WalletViewModel(accountRepo, accountStorage, card, doleDir.absolutePath)
        }

        WalletApp(viewModel)
    }
}