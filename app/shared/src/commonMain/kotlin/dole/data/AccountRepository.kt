package dole.data

import dole.data.models.StoredAccount
import dole.utils.SecureStorage

class AccountRepository(private val secureStorage: SecureStorage, private val normalStorage: AccountStorage) {

    fun getAllAccounts(): List<StoredAccount> {
        return normalStorage.getAccountsList()
    }

    fun getAccount(id: String): StoredAccount? {
        return normalStorage.getAccountsList().find { it.id == id }
    }

    suspend fun createAccount(id: String, name: String, pin: String) {
        val currentAccounts = normalStorage.getAccountsList().toMutableList()
        currentAccounts.removeAll { it.id == id }
        currentAccounts.add(StoredAccount(id, name, ""))
        normalStorage.saveAccountsList(currentAccounts)

        secureStorage.savePinSecurely(id, pin)
    }

    fun updateAccountName(id: String, newName: String) {
        val currentAccounts = normalStorage.getAccountsList().toMutableList()
        val index = currentAccounts.indexOfFirst { it.id == id }
        if (index != -1) {
            val acc = currentAccounts[index]
            currentAccounts[index] = StoredAccount(acc.id, newName, acc.pinHash)
            normalStorage.saveAccountsList(currentAccounts)
        }
    }

    suspend fun verifyPin(accountId: String, pin: String): Boolean {
        return secureStorage.getPinSecurely(accountId) == pin
    }

    suspend fun changePin(id: String, newPin: String) {
        secureStorage.savePinSecurely(id, newPin)
    }

    suspend fun deleteAccount(id: String) {
        val currentAccounts = normalStorage.getAccountsList().toMutableList()
        currentAccounts.removeAll { it.id == id }
        normalStorage.saveAccountsList(currentAccounts)
        normalStorage.clearAccountData(id)
        secureStorage.deletePinSecurely(id)
    }

    fun isMinter(id: String): Boolean = normalStorage.isMinter(id)

    fun setMinterStatus(id: String, isMinter: Boolean) {
        normalStorage.setMinterStatus(id, isMinter)
    }
}