package dole.data

import com.russhwolf.settings.Settings
import dole.data.models.StoredAccount
import dole.utils.SecureStorage
import kotlinx.serialization.json.Json

class AccountRegistry(
    private val settings: Settings,
    private val secureStorage: SecureStorage,
    private val preferences: AccountPreferences,
    private val cardSyncState: CardSyncState
) {
    fun getAllAccounts(): List<StoredAccount> {
        val stored = settings.getStringOrNull(KEY_ACCOUNTS) ?: return emptyList()
        return try {
            Json.decodeFromString(stored)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getAccount(id: String): StoredAccount? = getAllAccounts().find { it.id == id }

    suspend fun createAccount(id: String, name: String, pin: String) {
        val accounts = getAllAccounts().filterNot { it.id == id } + StoredAccount(id, name, "")
        saveAccounts(accounts)
        secureStorage.savePinSecurely(id, pin)
    }

    fun updateAccountName(id: String, newName: String) {
        val accounts = getAllAccounts().map { if (it.id == id) StoredAccount(it.id, newName, it.pinHash) else it }
        saveAccounts(accounts)
    }

    suspend fun verifyPin(accountId: String, pin: String): Boolean = secureStorage.getPinSecurely(accountId) == pin

    suspend fun changePin(id: String, newPin: String) {
        secureStorage.savePinSecurely(id, newPin)
    }

    suspend fun deleteAccount(id: String) {
        saveAccounts(getAllAccounts().filterNot { it.id == id })
        forget(id)
    }

    suspend fun deleteAllAccounts(): Int {
        val accounts = getAllAccounts()
        for (account in accounts) forget(account.id)
        saveAccounts(emptyList())
        return accounts.size
    }

    fun isMinter(id: String): Boolean = settings.getBoolean(minterKey(id), false)

    fun setMinterStatus(id: String, isMinter: Boolean) {
        settings.putBoolean(minterKey(id), isMinter)
    }

    private suspend fun forget(id: String) {
        settings.remove(minterKey(id))
        preferences.clear(id)
        cardSyncState.clear(id)
        secureStorage.deletePinSecurely(id)
    }

    private fun saveAccounts(accounts: List<StoredAccount>) {
        settings.putString(KEY_ACCOUNTS, Json.encodeToString(accounts))
    }

    private fun minterKey(id: String) = "minter_$id"

    private companion object {
        const val KEY_ACCOUNTS = "SAVED_ACCOUNTS_LIST"
    }
}
