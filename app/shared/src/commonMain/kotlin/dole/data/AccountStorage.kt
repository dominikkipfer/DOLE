package dole.data

import com.russhwolf.settings.Settings

class AccountStorage {
    private val settings =
        Settings()

    fun saveAccountId(id: String) {
        settings.putString("CURRENT_ACCOUNT_ID", id)
    }

    fun getAccountId(): String? {
        return settings.getStringOrNull("CURRENT_ACCOUNT_ID")
    }

    fun clearAccount() {
        settings.remove("CURRENT_ACCOUNT_ID")
    }
}