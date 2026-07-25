package dole.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.Foundation.*
import platform.Security.*

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosSecureStorage : SecureStorage {

    override val isBiometricSupported: Boolean = true

    override fun hasSavedPin(accountId: String): Boolean {
        val account = CFBridgingRetain(accountId)
        try {
            val status = withKeychainQuery(
                mapOf(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrAccount to account,
                    kSecReturnData to kCFBooleanFalse
                )
            ) { SecItemCopyMatching(it, null) }
            return status == errSecSuccess
        } finally {
            CFBridgingRelease(account)
        }
    }

    override suspend fun savePinSecurely(accountId: String, pin: String) {
        val pinData = CFBridgingRetain(pin.toNSData())
        val account = CFBridgingRetain(accountId)
        val bioAccount = CFBridgingRetain(accountId + BIO_SUFFIX)
        val accessControl = SecAccessControlCreateWithFlags(
            kCFAllocatorDefault,
            kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            kSecAccessControlBiometryAny,
            null
        )
        try {
            withKeychainQuery(
                mapOf(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrAccount to account
                )
            ) { SecItemDelete(it) }
            withKeychainQuery(
                mapOf(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrAccount to account,
                    kSecValueData to pinData,
                    kSecAttrAccessible to kSecAttrAccessibleWhenUnlockedThisDeviceOnly
                )
            ) { SecItemAdd(it, null) }

            if (accessControl != null) {
                withKeychainQuery(
                    mapOf(
                        kSecClass to kSecClassGenericPassword,
                        kSecAttrAccount to bioAccount
                    )
                ) { SecItemDelete(it) }
                withKeychainQuery(
                    mapOf(
                        kSecClass to kSecClassGenericPassword,
                        kSecAttrAccount to bioAccount,
                        kSecValueData to pinData,
                        kSecAttrAccessControl to accessControl
                    )
                ) { SecItemAdd(it, null) }
            }
        } finally {
            accessControl?.let { CFRelease(it) }
            CFBridgingRelease(bioAccount)
            CFBridgingRelease(account)
            CFBridgingRelease(pinData)
        }
    }

    override suspend fun getPinSecurely(accountId: String): String? = getPin(accountId, promptTitle = null)

    override suspend fun getPinWithBiometrics(accountId: String): String? = getPin(accountId + BIO_SUFFIX, promptTitle = "Login to Dole Wallet")

    override suspend fun deletePinSecurely(accountId: String) {
        listOf(accountId, accountId + BIO_SUFFIX).forEach { acc ->
            val account = CFBridgingRetain(acc)
            try {
                withKeychainQuery(
                    mapOf(
                        kSecClass to kSecClassGenericPassword,
                        kSecAttrAccount to account
                    )
                ) { SecItemDelete(it) }
            } finally {
                CFBridgingRelease(account)
            }
        }
    }

    private fun getPin(account: String, promptTitle: String?): String? {
        val accountRef = CFBridgingRetain(account)
        val prompt = promptTitle?.let { CFBridgingRetain(it) }
        try {
            val entries = mutableMapOf<CFStringRef?, CFTypeRef?>(
                kSecClass to kSecClassGenericPassword,
                kSecAttrAccount to accountRef,
                kSecReturnData to kCFBooleanTrue,
                kSecMatchLimit to kSecMatchLimitOne
            )
            if (prompt != null) entries[kSecUseOperationPrompt] = prompt

            return memScoped {
                val resultPtr = alloc<CFTypeRefVar>()
                val status = withKeychainQuery(entries) { SecItemCopyMatching(it, resultPtr.ptr) }
                if (status == errSecSuccess) {
                    (CFBridgingRelease(resultPtr.value) as? NSData)?.toKString()
                } else {
                    null
                }
            }
        } finally {
            prompt?.let { CFBridgingRelease(it) }
            CFBridgingRelease(accountRef)
        }
    }

    private fun <T> withKeychainQuery(
        entries: Map<CFStringRef?, CFTypeRef?>,
        block: (CFDictionaryRef) -> T
    ): T {
        val dict = CFDictionaryCreateMutable(kCFAllocatorDefault, entries.size.convert(), null, null)
            ?: throw IllegalStateException("Unable to allocate keychain query")
        try {
            for ((key, value) in entries) {
                CFDictionaryAddValue(dict, key, value)
            }
            return block(dict)
        } finally {
            CFRelease(dict)
        }
    }

    private fun String.toNSData(): NSData {
        val bytes = this.encodeToByteArray()
        if (bytes.isEmpty()) return NSData()
        return bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.convert())
        }
    }

    private fun NSData.toKString(): String? {
        val len = this.length.toInt()
        if (len == 0) return ""
        val ptr = this.bytes ?: return null
        return ptr.readBytes(len).decodeToString()
    }

    private companion object {
        const val BIO_SUFFIX = ".bio"
    }
}

@Composable
actual fun rememberSecureStorage(): SecureStorage = remember { IosSecureStorage() }
