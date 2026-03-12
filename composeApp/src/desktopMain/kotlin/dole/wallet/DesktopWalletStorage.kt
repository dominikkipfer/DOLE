package dole.wallet

import java.io.File

class DesktopWalletStorage(private val filePath: String) : WalletStorage {
    override fun readData(): String? {
        val file = File(filePath)
        return if (file.exists()) file.readText() else null
    }

    override fun writeData(data: String) {
        val file = File(filePath)
        file.parentFile?.mkdirs()
        file.writeText(data)
    }
}