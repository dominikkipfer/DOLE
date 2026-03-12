package dole.wallet

data class StoredAccount(
    val id: String,
    val name: String,
    val pinHash: String
)