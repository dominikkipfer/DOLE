package dole.data

import dole.data.models.StoredAccount

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