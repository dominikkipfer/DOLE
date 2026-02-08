package dole.crypto;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

import dole.Constants;

public class ProtocolSerializer {

    private ProtocolSerializer() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static byte[] buildMintBurnPayload(byte[] pin, int amount) {
        validatePin(pin);
        validateAmount(amount);

        ByteBuffer buffer = ByteBuffer.allocate(Constants.APDU_MINT_BURN_SIZE);
        buffer.putInt(amount);

        return buffer.array();
    }

    public static byte[] buildSendPayload(byte[] pin, String targetIdHex, int amount) {
        validatePin(pin);
        validateIdHex(targetIdHex);
        validateAmount(amount);

        byte[] targetId = CryptoUtils.hexToBytes(targetIdHex);

        ByteBuffer buffer = ByteBuffer.allocate(Constants.APDU_SEND_SIZE);
        buffer.put(targetId);
        buffer.putInt(amount);

        return buffer.array();
    }

    public static byte[] buildReceivePayload(byte[] pin, byte[] senderPublicKey, byte[] signature, byte[] logPayload) {
        validatePin(pin);

        if (senderPublicKey == null || senderPublicKey.length == 0) {
            throw new IllegalArgumentException("Sender public key cannot be null or empty");
        }
        if (signature == null || signature.length == 0) {
            throw new IllegalArgumentException("Signature cannot be null or empty");
        }
        if (logPayload == null || logPayload.length != Constants.LOG_PAYLOAD_SIZE) {
            throw new IllegalArgumentException("Log payload must be exactly " + Constants.LOG_PAYLOAD_SIZE + " bytes");
        }

        int totalSize = Constants.LEN_SIZE + senderPublicKey.length +
                Constants.LEN_SIZE + signature.length + Constants.LOG_PAYLOAD_SIZE;

        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        buffer.putShort((short) senderPublicKey.length);
        buffer.put(senderPublicKey);
        buffer.putShort((short) signature.length);
        buffer.put(signature);
        buffer.put(logPayload);

        return buffer.array();
    }

    public static byte[] buildAddPeerPayload(byte[] certificate, byte[] publicKey) {
        if (certificate == null || certificate.length == 0) {
            throw new IllegalArgumentException("Certificate cannot be null or empty");
        }
        if (publicKey == null || publicKey.length == 0) {
            throw new IllegalArgumentException("Public key cannot be null or empty");
        }

        int totalSize = Constants.LEN_SIZE + certificate.length + Constants.LEN_SIZE + publicKey.length;

        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        buffer.putShort((short) certificate.length);
        buffer.put(certificate);
        buffer.putShort((short) publicKey.length);
        buffer.put(publicKey);

        return buffer.array();
    }

    public static byte[] buildLogPayload(int seq, byte[] prevHash, byte type, byte[] author, byte[] target, int goc) {
        if (prevHash == null || prevHash.length != Constants.HASH_SIZE) {
            throw new IllegalArgumentException("prevHash must be exactly " + Constants.HASH_SIZE + " bytes");
        }
        if (!Constants.OperationType.isValidOpCode(type)) {
            throw new IllegalArgumentException("Invalid operation type: " + type);
        }
        if (author == null || author.length != Constants.ID_SIZE) {
            throw new IllegalArgumentException("author must be exactly " + Constants.ID_SIZE + " bytes");
        }

        if (type == Constants.OP_GENESIS || type == Constants.OP_MINT || type == Constants.OP_BURN) target = null;
        else if (type == Constants.OP_SEND) {
            if (target == null) throw new IllegalArgumentException("Target ID is required for SEND operation");
        }

        if (target != null && target.length != Constants.ID_SIZE) {
            throw new IllegalArgumentException("target must be exactly " + Constants.ID_SIZE + " bytes or null");
        }

        ByteBuffer buffer = ByteBuffer.allocate(Constants.LOG_PAYLOAD_SIZE);
        buffer.putInt(seq);
        buffer.put(prevHash);
        buffer.put(type);
        buffer.put(author);
        buffer.put(Objects.requireNonNullElseGet(target, () -> new byte[Constants.ID_SIZE]));
        buffer.putInt(goc);

        return buffer.array();
    }

    public static byte[] buildLogPayloadFromHex(int seq, String prevHashHex, byte type, String authorHex, String targetHex, int goc) {
        byte[] prevHash = validateAndConvertHash(prevHashHex);
        byte[] author = validateAndConvertId(authorHex);
        byte[] target = (targetHex != null && !isZeroHex(targetHex)) ? validateAndConvertId(targetHex) : null;

        return buildLogPayload(seq, prevHash, type, author, target, goc);
    }

    public static int extractSeq(byte[] logPayload) {
        validateLogPayload(logPayload);
        return ByteBuffer.wrap(logPayload).getInt(Constants.LOG_OFFSET_SEQ);
    }

    public static byte extractType(byte[] logPayload) {
        validateLogPayload(logPayload);
        return logPayload[Constants.LOG_OFFSET_TYPE];
    }

    public static int extractGoc(byte[] logPayload) {
        validateLogPayload(logPayload);
        return ByteBuffer.wrap(logPayload).getInt(Constants.LOG_OFFSET_GOC);
    }

    public static byte[] extractPrevHash(byte[] logPayload) {
        validateLogPayload(logPayload);
        return Arrays.copyOfRange(logPayload, Constants.LOG_OFFSET_PREV_HASH,
                Constants.LOG_OFFSET_PREV_HASH + Constants.HASH_SIZE);
    }

    public static byte[] extractAuthor(byte[] logPayload) {
        validateLogPayload(logPayload);
        return Arrays.copyOfRange(logPayload, Constants.LOG_OFFSET_AUTHOR,
                Constants.LOG_OFFSET_AUTHOR + Constants.ID_SIZE);
    }

    public static byte[] extractTarget(byte[] logPayload) {
        validateLogPayload(logPayload);
        return Arrays.copyOfRange(logPayload, Constants.LOG_OFFSET_TARGET,
                Constants.LOG_OFFSET_TARGET + Constants.ID_SIZE);
    }

    private static void validateAmount(int amount) {
        if (amount <= 0) throw new IllegalArgumentException("Amount must be over zero");
    }

    private static void validateIdHex(String idHex) {
        if (idHex == null || idHex.length() != Constants.ID_SIZE * Constants.HEX_CHARS_PER_BYTE) {
            throw new IllegalArgumentException("Invalid ID hex length");
        }

        try {
            CryptoUtils.hexToBytes(idHex);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid hex string for ID: " + idHex);
        }
    }

    private static void validateLogPayload(byte[] logPayload) {
        if (logPayload == null || logPayload.length != Constants.LOG_PAYLOAD_SIZE) {
            throw new IllegalArgumentException("Invalid log payload size");
        }
    }

    private static void validatePin(byte[] pin) {
        if (pin == null || pin.length != Constants.PIN_SIZE) {
            throw new IllegalArgumentException("PIN must be exactly " + Constants.PIN_SIZE + " bytes");
        }
        for (byte b : pin) {
            if (b < '0' || b > '9') throw new IllegalArgumentException("PIN must be digits");
        }
    }

    private static byte[] validateAndConvertHash(String hashHex) {
        if (hashHex == null || hashHex.length() != Constants.HASH_SIZE * Constants.HEX_CHARS_PER_BYTE) {
            throw new IllegalArgumentException("Invalid Hash hex length");
        }
        return CryptoUtils.hexToBytes(hashHex);
    }

    private static byte[] validateAndConvertId(String idHex) {
        validateIdHex(idHex);
        return CryptoUtils.hexToBytes(idHex);
    }

    private static boolean isZeroHex(String hex) {
        return hex == null || hex.matches("0+");
    }

    public static byte[] validateAndConvertPin(char[] pin) {
        if (pin == null || pin.length != Constants.PIN_SIZE) {
            throw new IllegalArgumentException("PIN must be exactly " + Constants.PIN_SIZE + " digits");
        }

        byte[] pinBytes = new byte[Constants.PIN_SIZE];
        for (int i = 0; i < Constants.PIN_SIZE; i++) pinBytes[i] = (byte) pin[i];

        validatePin(pinBytes);
        return pinBytes;
    }
}