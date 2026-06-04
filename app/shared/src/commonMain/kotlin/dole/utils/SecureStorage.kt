package dole.utils

import androidx.compose.runtime.Composable

interface SecureStorage {
    val isBiometricSupported: Boolean
    suspend fun savePinSecurely(accountId: String, pin: String)
    suspend fun getPinSecurely(accountId: String): String?
    suspend fun getPinWithBiometrics(accountId: String): String?
    suspend fun deletePinSecurely(accountId: String)
    fun hasSavedPin(accountId: String): Boolean
}

@Composable
expect fun rememberSecureStorage(): SecureStorage