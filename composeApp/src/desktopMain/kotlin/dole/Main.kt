package dole

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dole.card.PCSmartCard
import dole.gui.WalletApp
import dole.gui.WalletViewModel
import dole.ledger.Ledger
import dole.ledger.LedgerService
import dole.wallet.SettingsService
import dole.ditto.setupDitto
import dole.wallet.DesktopWalletStorage
import org.jetbrains.compose.resources.decodeToSvgPainter
import java.io.File

fun main() = application {
    val viewModel = remember {
        val userHome = System.getProperty("user.home")
        val doleDir = File(userHome, ".dole")
        if (!doleDir.exists()) doleDir.mkdirs()

        val storage = DesktopWalletStorage(File(doleDir, "wallet_data.json").absolutePath)
        val settingsService = SettingsService(storage)
        val ledger = Ledger()
        settingsService.attachLedger(ledger)

        val ditto = setupDitto(Constants.DITTO_APP_ID, Constants.DITTO_PLAYGROUND_TOKEN)
        val ledgerService = LedgerService(ditto)

        WalletViewModel(settingsService, ledger, ledgerService).apply {
            setCardImplementation(PCSmartCard())
        }
    }

    val icon = remember {
        this::class.java.getResourceAsStream("/DOLE.svg")!!.readAllBytes().decodeToSvgPainter(density = Density(1f))
    }

    Window(onCloseRequest = ::exitApplication, title = "DOLE", icon = icon) {
        WalletApp(viewModel)
    }
}