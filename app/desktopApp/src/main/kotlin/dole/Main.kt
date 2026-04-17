package dole

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dole.ui.screens.WalletApp
import dole.ui.screens.WalletViewModel
import org.jetbrains.compose.resources.decodeToSvgPainter
import java.io.File

fun main() = application {
    val viewModel = remember {
        val userHome = System.getProperty("user.home")
        val doleDir = File(userHome, ".dole")
        if (!doleDir.exists()) doleDir.mkdirs()

        WalletViewModel(doleDir.absolutePath)
    }

    val icon = remember {
        this::class.java.getResourceAsStream("/DOLE.svg")!!.readAllBytes().decodeToSvgPainter(density = Density(1f))
    }

    Window(onCloseRequest = ::exitApplication, title = "DOLE", icon = icon) {
        WalletApp(viewModel)
    }
}