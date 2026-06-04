package dole.utils

import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.Foundation.*
import platform.Security.*

class IosSecureStorage : SecureStorage {

    override val isBiometricSupported: Boolean = true

    override fun hasSavedPin(accountId: String): Boolean {
        val query = mutableMapOf<Any?, Any?>(
            kSecClass to kSecClassGenericPassword,
            kSecAttrAccount to accountId,
            kSecReturnData to kCFBooleanFalse
        )
        val status = SecItemCopyMatching(query as CFDictionaryRef, null)
        return status == errSecSuccess
    }

    override suspend fun savePinSecurely(accountId: String, pin: String) {
        val accessControl = SecAccessControlCreateWithFlags(
            kCFAllocatorDefault,
            kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            kSecAccessControlBiometryAny,
            null
        )
        val pinData = (pin as NSString).dataUsingEncoding(NSUTF8StringEncoding)
        val query = mutableMapOf<Any?, Any?>(
            kSecClass to kSecClassGenericPassword,
            kSecAttrAccount to accountId,
            kSecValueData to pinData,
            kSecAttrAccessControl to accessControl
        )

        SecItemDelete(query as CFDictionaryRef)
        SecItemAdd(query as CFDictionaryRef, null)
    }

    override suspend fun getPinSecurely(accountId: String, promptTitle: String): String? {
        val query = mutableMapOf<Any?, Any?>(
            kSecClass to kSecClassGenericPassword,
            kSecAttrAccount to accountId,
            kSecReturnData to kCFBooleanTrue,
            kSecMatchLimit to kSecMatchLimitOne,
            kSecUseOperationPrompt to promptTitle
        )

        var result: CFTypeRef? = null
        val status = memScoped {
            val resultPtr = alloc<CFTypeRefVar>()
            val secStatus = SecItemCopyMatching(query as CFDictionaryRef, resultPtr.ptr)
            result = resultPtr.value
            secStatus
        }

        if (status == errSecSuccess && result != null) {
            val data = result as NSData
            return NSString.create(data, NSUTF8StringEncoding) as String
        }
        return null
    }
}

actual fun rememberSecureStorage(): SecureStorage = IosSecureStorage()