package dole.data

import com.russhwolf.settings.Settings
import dole.viewmodel.PendingAction
import kotlinx.serialization.json.Json

class CardSyncState(private val settings: Settings) {

    fun getPendingActions(accountId: String): List<PendingAction> {
        val stored = settings.getStringOrNull(pendingKey(accountId)) ?: return emptyList()
        return try {
            Json.decodeFromString(stored)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun savePendingActions(accountId: String, actions: List<PendingAction>) {
        settings.putString(pendingKey(accountId), Json.encodeToString(actions))
    }

    fun getLastReceived(accountId: String): Map<String, Long> {
        val stored = settings.getStringOrNull(receivedKey(accountId)) ?: return emptyMap()
        return try {
            Json.decodeFromString(stored)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun saveLastReceived(accountId: String, receivedMap: Map<String, Long>) {
        settings.putString(receivedKey(accountId), Json.encodeToString(receivedMap))
    }

    fun clear(accountId: String) {
        settings.remove(pendingKey(accountId))
        settings.remove(receivedKey(accountId))
    }

    private fun pendingKey(accountId: String) = "pending_$accountId"
    private fun receivedKey(accountId: String) = "recv_$accountId"
}
