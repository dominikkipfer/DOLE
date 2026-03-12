package dole.wallet

import android.content.Context
import java.io.File

class AndroidWalletStorage(private val context: Context) : WalletStorage {
    private val file get() = File(context.filesDir, "wallet_data.json")

    override fun readData(): String? {
        return if (file.exists()) file.readText() else null
    }

    override fun writeData(data: String) {
        file.writeText(data)
    }
}