package dole.data

import com.russhwolf.settings.Settings
import dole.data.models.StoredAccount
import dole.viewmodel.PendingAction
import kotlinx.serialization.json.Json

class AccountStorage(private val settings: Settings) {

    fun setMinterStatus(accountId: String, isMinter: Boolean) {
        settings.putBoolean("minter_$accountId", isMinter)
    }

    fun isMinter(accountId: String): Boolean {
        return settings.getBoolean("minter_$accountId", false)
    }

    fun saveLastReceived(accountId: String, receivedMap: Map<String, Long>) {
        val jsonString = Json.encodeToString(receivedMap)
        settings.putString("recv_$accountId", jsonString)
    }

    fun getLastReceived(accountId: String): Map<String, Long> {
        val jsonString = settings.getStringOrNull("recv_$accountId") ?: return emptyMap()
        return try {
            Json.decodeFromString(jsonString)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun savePendingActions(accountId: String, actions: List<PendingAction>) {
        val jsonString = Json.encodeToString(actions)
        settings.putString("pending_$accountId", jsonString)
    }

    fun getPendingActions(accountId: String): List<PendingAction> {
        val jsonString = settings.getStringOrNull("pending_$accountId") ?: return emptyList()
        return try {
            Json.decodeFromString(jsonString)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun isScreenCaptureBlocked(accountId: String): Boolean {
        return settings.getBoolean("screencap_$accountId", false)
    }

    fun setScreenCaptureBlocked(accountId: String, blocked: Boolean) {
        settings.putBoolean("screencap_$accountId", blocked)
    }

    fun isBiometricsEnabled(accountId: String): Boolean {
        return settings.getBoolean("bio_$accountId", false)
    }

    fun setBiometricsEnabled(accountId: String, enabled: Boolean) {
        settings.putBoolean("bio_$accountId", enabled)
    }

    fun saveAccountsList(accounts: List<StoredAccount>) {
        val jsonString = Json.encodeToString(accounts)
        settings.putString("SAVED_ACCOUNTS_LIST", jsonString)
    }

    fun getAccountsList(): List<StoredAccount> {
        val jsonString = settings.getStringOrNull("SAVED_ACCOUNTS_LIST") ?: return emptyList()
        return try {
            Json.decodeFromString(jsonString)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clearAccountData(accountId: String) {
        settings.remove("minter_$accountId")
        settings.remove("recv_$accountId")
        settings.remove("pending_$accountId")
        settings.remove("screencap_$accountId")
        settings.remove("bio_$accountId")
    }
}