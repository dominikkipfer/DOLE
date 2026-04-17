package dole.core

interface UIStateListener {
    fun onStateUpdated(balance: Int, historyJson: String)
}

expect object CoreWrapper {
    fun initPrototype(listener: UIStateListener, storagePath: String)
    fun mint(amount: Int)
    fun burn(amount: Int)
    fun send(targetPubKey: String, amount: Int)
}