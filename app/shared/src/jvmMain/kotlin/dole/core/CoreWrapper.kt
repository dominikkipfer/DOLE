@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
package dole.core

actual object CoreWrapper {
    private var engine: PrototypeEngine? = null

    actual fun initPrototype(listener: UIStateListener, storagePath: String) {
        val rustListener = object : LedgerStateListener {
            override fun onStateUpdated(balance: Int, transactionHistoryJson: String) {
                listener.onStateUpdated(balance, transactionHistoryJson)
            }
        }
        engine = PrototypeEngine.initPrototype(rustListener, storagePath)
    }

    actual fun mint(amount: Int) {
        engine?.mint(amount)
    }

    actual fun burn(amount: Int) {
        engine?.burn(amount)
    }

    actual fun send(targetPubKey: String, amount: Int) {
        engine?.send(targetPubKey, amount)
    }
}