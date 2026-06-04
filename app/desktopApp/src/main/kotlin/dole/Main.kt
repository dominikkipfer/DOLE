package dole

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.russhwolf.settings.PreferencesSettings
import dole.card.PCSmartCard
import dole.core.CoreWrapper
import dole.data.AccountRepositoryImpl
import dole.data.AccountStorage
import dole.utils.DesktopSecureStorage
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

    CoreWrapper.startGlobalSync(doleDir.absolutePath)

    Window(
        onCloseRequest = {
            CoreWrapper.stopGlobalSync()
            exitApplication()
        },
        title = "DOLE",
        icon = icon
    ) {
        val secureStorage = remember { DesktopSecureStorage() }

        val accountRepo = remember {
            AccountRepositoryImpl(secureStorage, accountStorage)
        }

        val card = remember {
            PCSmartCard()
        }

        val viewModel = remember {
            WalletViewModel(accountRepo, accountStorage, card, doleDir.absolutePath)
        }

        WalletApp(viewModel)
    }
}