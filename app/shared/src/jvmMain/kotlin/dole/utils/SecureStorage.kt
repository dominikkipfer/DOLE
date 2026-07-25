package dole.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.File
import java.security.KeyStore
import java.util.prefs.Preferences
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec

class DesktopSecureStorage : SecureStorage {
    override val isBiometricSupported: Boolean = false

    private val prefs = Preferences.userRoot().node("dole_wallet_secure_prefs")
    private val keyAlias = "dole_desktop_master_key"

    override fun hasSavedPin(accountId: String): Boolean {
        return prefs.get("enc_$accountId", null) != null
    }

    override suspend fun savePinSecurely(accountId: String, pin: String) {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val secretKey = getOrCreateSecretKey()
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)

        val encryptedBytes = cipher.doFinal(pin.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv

        prefs.putByteArray("enc_$accountId", encryptedBytes)
        prefs.putByteArray("iv_$accountId", iv)
        prefs.flush()
    }

    override suspend fun getPinSecurely(accountId: String): String? {
        val encryptedBytes = prefs.getByteArray("enc_$accountId", null) ?: return null
        val iv = prefs.getByteArray("iv_$accountId", null) ?: return null

        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKey = getOrCreateSecretKey()
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))

            val decryptedBytes = cipher.doFinal(encryptedBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun getPinWithBiometrics(accountId: String): String? = null

    override suspend fun deletePinSecurely(accountId: String) {
        prefs.remove("enc_$accountId")
        prefs.remove("iv_$accountId")
        prefs.flush()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val userHome = System.getProperty("user.home")
        val doleDir = File(userHome, ".dole")
        if (!doleDir.exists()) doleDir.mkdirs()

        val keyStoreFile = File(doleDir, "desktop_master.keystore")
        val password = "dole_local_secure".toCharArray()

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())

        if (keyStoreFile.exists()) {
            keyStoreFile.inputStream().use { keyStore.load(it, password) }
            if (keyStore.containsAlias(keyAlias)) return keyStore.getKey(keyAlias, password) as SecretKey
        } else {
            keyStore.load(null, password)
        }

        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        val secretKey = keyGen.generateKey()

        keyStore.setKeyEntry(keyAlias, secretKey, password, null)
        keyStoreFile.outputStream().use { keyStore.store(it, password) }

        return secretKey
    }
}

@Composable
actual fun rememberSecureStorage(): SecureStorage = remember { DesktopSecureStorage() }