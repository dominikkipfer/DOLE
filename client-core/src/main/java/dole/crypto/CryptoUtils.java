package dole.crypto;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

import dole.Constants;

public class CryptoUtils {

    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public static byte[] hexToBytes(String s) {
        if (s == null || s.isEmpty()) return new byte[0];
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    public static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance(Constants.HASH_ALGORITHM).digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] calculatePersonId(byte[] publicKeyBytes) {
        byte[] hash = sha256(publicKeyBytes);
        return Arrays.copyOfRange(hash, 0, 20);
    }

    public static String getPersonIdAsHex(byte[] publicKeyBytes) {
        return bytesToHex(calculatePersonId(publicKeyBytes));
    }

    public static boolean verifySignature(PublicKey key, byte[] data, byte[] signature) {
        try {
            Signature s = Signature.getInstance(Constants.SIGNATURE_ALGORITHM);
            s.initVerify(key);
            s.update(data);
            return s.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    public static PublicKey decodePublicKey(byte[] encodedKey) {
        try {
            KeyFactory kf = KeyFactory.getInstance(Constants.KEY_ALGORITHM);

            if (encodedKey.length == 65 && encodedKey[0] == 0x04) {
                AlgorithmParameters params = AlgorithmParameters.getInstance(Constants.KEY_ALGORITHM);
                params.init(new ECGenParameterSpec(Constants.EC_CURVE));
                ECParameterSpec ecSpec = params.getParameterSpec(ECParameterSpec.class);

                byte[] x = new byte[32];
                byte[] y = new byte[32];
                System.arraycopy(encodedKey, 1, x, 0, 32);
                System.arraycopy(encodedKey, 33, y, 0, 32);

                ECPoint point = new ECPoint(new BigInteger(1, x), new BigInteger(1, y));
                return kf.generatePublic(new ECPublicKeySpec(point, ecSpec));
            }

            return kf.generatePublic(new X509EncodedKeySpec(encodedKey));
        } catch (Exception e) {
            throw new RuntimeException("Invalid Key format", e);
        }
    }
}