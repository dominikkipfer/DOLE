package dole.utils

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidSecureStorage(private val activity: FragmentActivity) : SecureStorage {

    override val isBiometricSupported: Boolean = true
    private val sharedPrefs = activity.getSharedPreferences("dole_secure_prefs", Context.MODE_PRIVATE)
    private val keyAlias = "dole_biometric_key_v2"

    private class DecryptSession(val cipher: Cipher, val encryptedBytes: ByteArray)

    override fun hasSavedPin(accountId: String): Boolean {
        return sharedPrefs.contains("iv_$accountId") && sharedPrefs.contains("enc_$accountId")
    }

    override suspend fun savePinSecurely(accountId: String, pin: String) {
        val cipher = getCipher()
        val secretKey = getOrCreateSecretKey()
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)

        val encryptedBytes = cipher.doFinal(pin.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv

        sharedPrefs.edit {
            putString("enc_$accountId", Base64.encodeToString(encryptedBytes, Base64.DEFAULT))
            putString("iv_$accountId", Base64.encodeToString(iv, Base64.DEFAULT))
        }
    }

    override suspend fun getPinSecurely(accountId: String): String? {
        val session = prepareDecryption(accountId) ?: return null
        return try {
            String(session.cipher.doFinal(session.encryptedBytes), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun getPinWithBiometrics(accountId: String): String? {
        val session = prepareDecryption(accountId) ?: return null

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Login to Dole Wallet")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build()

                val biometricPrompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            try {
                                val decryptedBytes = result.cryptoObject?.cipher?.doFinal(session.encryptedBytes)
                                continuation.resume(decryptedBytes?.let { String(it, Charsets.UTF_8) })
                            } catch (_: Exception) { continuation.resume(null) }
                        }
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            continuation.resume(null)
                        }
                    })

                biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(session.cipher))
            }
        }
    }

    override suspend fun deletePinSecurely(accountId: String) {
        sharedPrefs.edit {
            remove("enc_$accountId")
            remove("iv_$accountId")
        }
    }

    private fun prepareDecryption(accountId: String): DecryptSession? {
        val encoded = sharedPrefs.getString("enc_$accountId", null) ?: return null
        val ivEncoded = sharedPrefs.getString("iv_$accountId", null) ?: return null
        val cipher = getCipher()
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), IvParameterSpec(Base64.decode(ivEncoded, Base64.DEFAULT)))
        return DecryptSession(cipher, Base64.decode(encoded, Base64.DEFAULT))
    }

    private fun getCipher(): Cipher = Cipher.getInstance("${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_CBC}/${KeyProperties.ENCRYPTION_PADDING_PKCS7}")

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.getKey(keyAlias, null)?.let { return it as SecretKey }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        keyGenerator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                .build()
        )
        return keyGenerator.generateKey()
    }
}

@Composable
actual fun rememberSecureStorage(): SecureStorage {
    val context = LocalContext.current
    val activity = context as? FragmentActivity ?: throw IllegalStateException("Activity must be a FragmentActivity for Biometrics")

    return remember(activity) { AndroidSecureStorage(activity) }
}
