package dole.transaction

interface Transaction {
    val id: String
    val seq: Int
    val author: String
}