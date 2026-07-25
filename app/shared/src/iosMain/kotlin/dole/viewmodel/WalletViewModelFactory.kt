package dole.viewmodel

import com.russhwolf.settings.NSUserDefaultsSettings
import dole.card.SmartCard
import dole.data.AccountPreferences
import dole.data.AccountRegistry
import dole.data.CardSyncState
import dole.utils.IosSecureStorage
import platform.Foundation.NSUserDefaults

fun createWalletViewModel(card: SmartCard, storagePath: String): WalletViewModel {
    val settings = NSUserDefaultsSettings(NSUserDefaults.standardUserDefaults)
    val secureStorage = IosSecureStorage()
    val accountPreferences = AccountPreferences(settings)
    val cardSyncState = CardSyncState(settings)
    val accounts = AccountRegistry(settings, secureStorage, accountPreferences, cardSyncState)
    return WalletViewModel(accounts, accountPreferences, cardSyncState, settings, card, storagePath)
}
