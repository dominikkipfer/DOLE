package dole

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dole.card.PCSmartCard
import dole.ledger.PCLedgerService
import dole.gui.WalletApp
import dole.gui.WalletViewModel
import dole.ledger.Ledger
import dole.wallet.SettingsService
import org.jetbrains.compose.resources.decodeToSvgPainter
import java.io.File
import kotlin.jvm.java

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
    val icon = remember {
        this::class.java.getResourceAsStream("/DOLE.svg")!!.readAllBytes().decodeToSvgPainter(density = Density(1f))
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "DOLE",
        icon = icon
    ) {
        WalletApp(viewModel)
    }
}