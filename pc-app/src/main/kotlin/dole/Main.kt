package dole

import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dole.card.PCSmartCard
import dole.ledger.PCLedgerService
import dole.gui.WalletApp
import dole.gui.WalletViewModel
import dole.ledger.Ledger
import dole.resources.DOLE
import dole.resources.Res
import dole.wallet.SettingsService
import org.jetbrains.compose.resources.painterResource
import java.io.File

fun main() = application {
    val userHome = System.getProperty("user.home")
    val storagePath = "$userHome${File.separator}.dole"
    val viewModel = remember {
        val settingsService = SettingsService(storagePath)
        val ledger = Ledger()
        settingsService.attachLedger(ledger)
        val ledgerService = PCLedgerService(storagePath, Constants.DITTO_APP_ID, Constants.DITTO_PLAYGROUND_TOKEN)
        WalletViewModel(settingsService, ledger, ledgerService).apply {
            setCardImplementation(
                PCSmartCard()
            )
        }
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "DOLE",
        icon = painterResource(Res.drawable.DOLE)
    ) {
        WalletApp(viewModel)
    }
}