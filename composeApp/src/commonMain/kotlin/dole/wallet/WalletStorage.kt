package dole.wallet

interface WalletStorage {
    fun readData(): String?
    fun writeData(data: String)
}