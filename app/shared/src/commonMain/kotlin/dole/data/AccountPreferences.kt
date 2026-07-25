package dole.data

import com.russhwolf.settings.Settings

class AccountPreferences(private val settings: Settings) {

    fun isBiometricsEnabled(accountId: String): Boolean = settings.getBoolean(bioKey(accountId), false)

    fun setBiometricsEnabled(accountId: String, enabled: Boolean) {
        settings.putBoolean(bioKey(accountId), enabled)
    }

    fun isScreenCaptureBlocked(accountId: String): Boolean = settings.getBoolean(captureKey(accountId), false)

    fun setScreenCaptureBlocked(accountId: String, blocked: Boolean) {
        settings.putBoolean(captureKey(accountId), blocked)
    }

    fun getKnownPinEpoch(accountId: String): Int = settings.getInt(pinEpochKey(accountId), -1)

    fun setKnownPinEpoch(accountId: String, epoch: Int) {
        settings.putInt(pinEpochKey(accountId), epoch)
    }

    fun clear(accountId: String) {
        settings.remove(bioKey(accountId))
        settings.remove(captureKey(accountId))
        settings.remove(pinEpochKey(accountId))
    }

    private fun bioKey(accountId: String) = "bio_$accountId"
    private fun captureKey(accountId: String) = "screencap_$accountId"
    private fun pinEpochKey(accountId: String) = "pinepoch_$accountId"
}
