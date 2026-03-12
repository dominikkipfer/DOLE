package dole.crypto

import dole.Constants
import java.math.BigInteger
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.X509EncodedKeySpec

object CryptoUtils {

    fun bytesToHex(bytes: ByteArray?): String {
        if (bytes == null) return ""
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(b.toUByte().toString(16).padStart(2, '0'))
        }
        return sb.toString()
    }

    fun hexToBytes(s: String?): ByteArray {
        if (s.isNullOrEmpty()) return ByteArray(0)
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    fun sha256(
        input: ByteArray?
    ): ByteArray {
        return try {
            MessageDigest.getInstance(Constants.HASH_ALGORITHM).digest(input)
        } catch (e: NoSuchAlgorithmException) {
            throw RuntimeException(e)
        }
    }

    fun calculatePersonId(
        publicKeyBytes: ByteArray?
    ): ByteArray {
        val hash = sha256(publicKeyBytes)
        return hash.copyOfRange(0, 20)
    }

    fun getPersonIdAsHex(
        publicKeyBytes: ByteArray?
    ): String {
        return bytesToHex(calculatePersonId(publicKeyBytes))
    }

    fun verifySignature(key: PublicKey?, data: ByteArray?, signature: ByteArray?): Boolean {
        if (key == null || data == null || signature == null) return false
        return try {
            val s = Signature.getInstance(Constants.SIGNATURE_ALGORITHM)
            s.initVerify(key)
            s.update(data)
            s.verify(signature)
        } catch (_: Exception) {
            false
        }
    }

    fun decodePublicKey(encodedKey: ByteArray): PublicKey? {
        return try {
            val kf = KeyFactory.getInstance(Constants.KEY_ALGORITHM)

            if (encodedKey.size == 65 && encodedKey[0].toInt() == 0x04) {
                val params = AlgorithmParameters.getInstance(Constants.KEY_ALGORITHM)
                params.init(ECGenParameterSpec(Constants.EC_CURVE))
                val ecSpec = params.getParameterSpec(ECParameterSpec::class.java)

                val x = ByteArray(32)
                val y = ByteArray(32)
                System.arraycopy(encodedKey, 1, x, 0, 32)
                System.arraycopy(encodedKey, 33, y, 0, 32)

                val point = ECPoint(BigInteger(1, x), BigInteger(1, y))
                kf.generatePublic(ECPublicKeySpec(point, ecSpec))
            } else {
                kf.generatePublic(X509EncodedKeySpec(encodedKey))
            }
        } catch (e: Exception) {
            throw RuntimeException("Invalid Key format", e)
        }
    }
}