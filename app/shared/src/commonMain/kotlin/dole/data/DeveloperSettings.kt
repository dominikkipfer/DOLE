package dole.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.russhwolf.settings.Settings
import dole.Constants
import dole.core.CoreWrapper
import dole.ui.theme.ThemeMode
import dole.viewmodel.PeerConnection
import dole.viewmodel.TransportStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

class DeveloperSettings(
    private val settings: Settings,
    private val accounts: AccountRegistry,
    private val scope: CoroutineScope,
    private val storagePath: String,
    private val onAccountsDeleted: () -> Unit,
    private val onLedgerCleared: () -> Unit,
    private val notify: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    var isEnabled by mutableStateOf(settings.getBoolean(KEY_DEVELOPER_MODE, false)); private set
    var themeMode by mutableStateOf(
        ThemeMode.entries.find { it.name == settings.getStringOrNull(KEY_THEME_MODE) } ?: ThemeMode.SYSTEM
    ); private set

    var isBleEnabled by mutableStateOf(settings.getBoolean(KEY_BLE_ENABLED, true)); private set
    var isLocalEnabled by mutableStateOf(settings.getBoolean(KEY_LOCAL_ENABLED, true)); private set
    var isInternetEnabled by mutableStateOf(settings.getBoolean(KEY_INTERNET_ENABLED, true)); private set

    var status by mutableStateOf(TransportStatus(false, false, false)); private set
    var peers by mutableStateOf<List<PeerConnection>>(emptyList()); private set

    private var isPermitted = false
    private var isEngineRunning = false
    private val lifecycle = Mutex()
    private var pollJob: Job? = null
    private var tapCount = 0

    fun onPermissionsGranted() {
        isPermitted = true
        CoreWrapper.refreshPermissions()
        applyTransports()
        if (isEnabled) startPolling()
    }

    fun stop() {
        isPermitted = false
        stopPolling()
        if (isEngineRunning) {
            isEngineRunning = false
            scope.launch(Dispatchers.IO) {
                lifecycle.withLock { CoreWrapper.stopGlobalSync() }
            }
        }
    }

    fun registerLogoTap() {
        if (isEnabled) return
        tapCount++
        if (tapCount >= Constants.DEV_MODE_TAP_COUNT) {
            tapCount = 0
            enable(true)
        }
    }

    fun enable(enabled: Boolean) {
        isEnabled = enabled
        settings.putBoolean(KEY_DEVELOPER_MODE, enabled)
        if (enabled) startPolling() else {
            stopPolling()
            resetToDefaults()
        }
    }

    fun enableBle(enabled: Boolean) {
        isBleEnabled = enabled
        settings.putBoolean(KEY_BLE_ENABLED, enabled)
        applyTransports()
    }

    fun enableLocal(enabled: Boolean) {
        isLocalEnabled = enabled
        settings.putBoolean(KEY_LOCAL_ENABLED, enabled)
        applyTransports()
    }

    fun enableInternet(enabled: Boolean) {
        isInternetEnabled = enabled
        settings.putBoolean(KEY_INTERNET_ENABLED, enabled)
        applyTransports()
    }

    fun selectThemeMode(mode: ThemeMode) {
        themeMode = mode
        settings.putString(KEY_THEME_MODE, mode.name)
    }

    fun resetToDefaults() {
        settings.remove(KEY_BLE_ENABLED)
        settings.remove(KEY_LOCAL_ENABLED)
        settings.remove(KEY_INTERNET_ENABLED)
        settings.remove(KEY_THEME_MODE)

        themeMode = ThemeMode.SYSTEM
        isBleEnabled = true
        isLocalEnabled = true
        isInternetEnabled = true
        applyTransports()
    }

    fun deleteAllAccounts() {
        scope.launch(Dispatchers.IO) {
            val removed = accounts.deleteAllAccounts()
            withContext(Dispatchers.Main) {
                onAccountsDeleted()
                notify("Removed $removed account(s) from this device.")
            }
        }
    }

    fun deleteAllCommits() {
        scope.launch(Dispatchers.IO) {
            val cleared = CoreWrapper.resetLedger(storagePath)
            withContext(Dispatchers.Main) {
                if (cleared) {
                    onLedgerCleared()
                    notify("Deleted all commits from the local ledger.")
                } else {
                    reportError("Could not clear the ledger.")
                }
            }
        }
    }

    private fun applyTransports() {
        CoreWrapper.setBleEnabled(isBleEnabled)
        CoreWrapper.setLocalEnabled(isLocalEnabled)
        CoreWrapper.setInternetEnabled(isInternetEnabled)
        val wantEngine = isPermitted && (isLocalEnabled || isBleEnabled || isInternetEnabled)
        if (wantEngine == isEngineRunning) return
        isEngineRunning = wantEngine
        scope.launch(Dispatchers.IO) {
            lifecycle.withLock {
                if (wantEngine) CoreWrapper.startGlobalSync(storagePath) else CoreWrapper.stopGlobalSync()
            }
        }
    }

    private fun startPolling() {
        if (pollJob != null) return
        pollJob = scope.launch {
            while (isActive) {
                val snapshot = withContext(Dispatchers.IO) {
                    CoreWrapper.connectedPeers() to CoreWrapper.transportStatus()
                }
                if (snapshot.first != peers) peers = snapshot.first
                if (snapshot.second != status) status = snapshot.second
                withContext(Dispatchers.IO) { CoreWrapper.benchLatencyReport() }?.let(notify)
                delay(POLL_INTERVAL)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        peers = emptyList()
        status = TransportStatus(false, false, false)
    }

    private companion object {
        const val KEY_DEVELOPER_MODE = "dev_mode_enabled"
        const val KEY_LOCAL_ENABLED = "dev_local_enabled"
        const val KEY_BLE_ENABLED = "dev_ble_enabled"
        const val KEY_INTERNET_ENABLED = "dev_internet_enabled"
        const val KEY_THEME_MODE = "dev_theme_mode"

        val POLL_INTERVAL = Constants.TRANSPORT_POLL_INTERVAL_MS.toLong().milliseconds
    }
}
