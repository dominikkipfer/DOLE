package dole.data

import dole.data.models.StoredAccount
import dole.utils.SecureStorage

interface AccountRepository {
    fun getAllAccounts(): List<StoredAccount>
    fun getAccount(id: String): StoredAccount?
    suspend fun verifyPin(accountId: String, pin: String): Boolean
    suspend fun createAccount(id: String, name: String, pin: String)
    suspend fun updateAccountName(id: String, newName: String)
    suspend fun changePin(id: String, newPin: String)
    suspend fun deleteAccount(id: String)
    fun isMinter(id: String): Boolean
    fun setMinterStatus(id: String, isMinter: Boolean)
}

class AccountRepositoryImpl(
    private val secureStorage: SecureStorage,
    private val normalStorage: AccountStorage
) : AccountRepository {

    override fun getAllAccounts(): List<StoredAccount> {
        return normalStorage.getAccountsList()
    }

    override fun getAccount(id: String): StoredAccount? {
        return normalStorage.getAccountsList().find { it.id == id }
    }

    override suspend fun createAccount(id: String, name: String, pin: String) {
        val currentAccounts = normalStorage.getAccountsList().toMutableList()
        currentAccounts.removeAll { it.id == id }
        currentAccounts.add(StoredAccount(id, name, ""))
        normalStorage.saveAccountsList(currentAccounts)

        secureStorage.savePinSecurely(id, pin)
    }

    override suspend fun updateAccountName(id: String, newName: String) {
        val currentAccounts = normalStorage.getAccountsList().toMutableList()
        val index = currentAccounts.indexOfFirst { it.id == id }
        if (index != -1) {
            val acc = currentAccounts[index]
            currentAccounts[index] = StoredAccount(acc.id, newName, acc.pinHash)
            normalStorage.saveAccountsList(currentAccounts)
        }
    }

    override suspend fun verifyPin(accountId: String, pin: String): Boolean {
        return secureStorage.getPinSecurely(accountId) == pin
    }

    override suspend fun changePin(id: String, newPin: String) {
        secureStorage.savePinSecurely(id, newPin)
    }

    override suspend fun deleteAccount(id: String) {
        val currentAccounts = normalStorage.getAccountsList().toMutableList()
        currentAccounts.removeAll { it.id == id }
        normalStorage.saveAccountsList(currentAccounts)
        normalStorage.clearAccountData(id)
        secureStorage.deletePinSecurely(id)
    }

    override fun isMinter(id: String): Boolean = normalStorage.isMinter(id)

    override fun setMinterStatus(id: String, isMinter: Boolean) {
        normalStorage.setMinterStatus(id, isMinter)
    }
}