@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
package dole.core

var swiftInitAction: ((UIStateListener, String) -> Unit)? = null
var swiftMintAction: ((Int) -> Unit)? = null
var swiftBurnAction: ((Int) -> Unit)? = null
var swiftSendAction: ((String, Int) -> Unit)? = null

actual object CoreWrapper {
	actual fun initPrototype(listener: UIStateListener, storagePath: String) {
		swiftInitAction?.invoke(listener, storagePath)
	}

	actual fun mint(amount: Int) {
		swiftMintAction?.invoke(amount)
	}

	actual fun burn(amount: Int) {
		swiftBurnAction?.invoke(amount)
	}

	actual fun send(targetPubKey: String, amount: Int) {
		swiftSendAction?.invoke(targetPubKey, amount)
	}
}
