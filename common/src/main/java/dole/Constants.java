package dole;

public final class Constants {

    private Constants() {
        throw new UnsupportedOperationException("Utility class");
    }

    // Ditto
    public static final String DITTO_APP_ID = "39f7863f-9751-4b9c-be12-433716726ab7";
    public static final String DITTO_PLAYGROUND_TOKEN = "abcca39a-4199-46a7-ba6a-1f13e64f9ca4";
    public static final String DITTO_AUTH_URL = "https://i83inp.cloud.dittolive.app";
    public static final String DITTO_WEBSOCKET_URL = "wss://i83inp.cloud.dittolive.app";

    // --- FILESYSTEM ---
    public static final String COLLECTION = "wallet_logs";
    public static final String FILE_WALLET_DATA = "wallet_data.json";

    // --- ERROR MESSAGES ---
    public static final String ERR_CARD_NOT_FOUND = "No card found. Please insert your smart card.";
    public static final String ERR_PIN_INVALID = "Invalid PIN. Please try again.";
    public static final String ERR_CODE_CARD_REMOVED = "SCARD_W_REMOVED_CARD";
    public static final String ERR_CODE_NO_READER = "No card reader found.";

    // Applet AID
    public static final String APPLET_AID_HEX = "010203040501";
    public static final byte[] APPLET_AID_BYTES = {
            (byte)0x01, (byte)0x02, (byte)0x03, (byte)0x04, (byte)0x05, (byte)0x01
    };

    // --- CRYPTO ---
    public static final String EC_CURVE = "secp256r1";
    public static final String HASH_ALGORITHM = "SHA-256";
    public static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";
    public static final String KEY_ALGORITHM = "EC";

    public static final byte[] ZERO_HASH = new byte[32];

    public static final byte[] ROOT_CA_BYTES = {
            (byte)0x04,
            (byte)0x36, (byte)0x2B, (byte)0x9D, (byte)0xBF, (byte)0xDE, (byte)0x93, (byte)0x5B, (byte)0x15,
            (byte)0x3C, (byte)0x59, (byte)0xE9, (byte)0xBD, (byte)0x80, (byte)0x89, (byte)0x03, (byte)0x7D,
            (byte)0x62, (byte)0xA7, (byte)0x2B, (byte)0x68, (byte)0xCC, (byte)0xDD, (byte)0x4C, (byte)0x8D,
            (byte)0x3A, (byte)0x1D, (byte)0xB9, (byte)0x16, (byte)0x74, (byte)0x96, (byte)0x7D, (byte)0x97,

            (byte)0x5B, (byte)0xFB, (byte)0x03, (byte)0x29, (byte)0x50, (byte)0xD7, (byte)0x5A, (byte)0xA3,
            (byte)0x05, (byte)0xB5, (byte)0x18, (byte)0xDE, (byte)0xC7, (byte)0xE9, (byte)0xF7, (byte)0x9E,
            (byte)0x8F, (byte)0x7D, (byte)0xD1, (byte)0x25, (byte)0x27, (byte)0xDF, (byte)0x01, (byte)0x19,
            (byte)0x8E, (byte)0xBF, (byte)0xDD, (byte)0xE0, (byte)0x18, (byte)0x78, (byte)0x0D, (byte)0x3B,
    };

    // --- SIZES ---
    public static final byte PIN_SIZE = 4;
    public static final short ID_SIZE = 20;
    public static final short HASH_SIZE = 32;
    public static final short GOC_SIZE = 4;
    public static final short LEN_SIZE = 2;
    public static final int HEX_CHARS_PER_BYTE = 2;

    // --- LOG STRUCTURE ---
    public static final short LOG_PAYLOAD_SIZE = 81;
    public static final short LOG_OFFSET_SEQ = 0;
    public static final short LOG_OFFSET_PREV_HASH = 4;
    public static final short LOG_OFFSET_TYPE = 36;
    public static final short LOG_OFFSET_AUTHOR = 37;
    public static final short LOG_OFFSET_TARGET = 57;
    public static final short LOG_OFFSET_GOC = 77;

    // --- JSON KEYS ---
    public static final String KEY_SEQ = "seq";
    public static final String KEY_PREV_HASH = "prevHash";
    public static final String KEY_TYPE = "type";
    public static final String KEY_AUTHOR = "author";
    public static final String KEY_TARGET = "target";
    public static final String KEY_GOC = "goc";
    public static final String KEY_SIGNATURE = "signature";
    public static final String KEY_PUBKEY = "publicKey";
    public static final String KEY_CERT = "certificate";

    // --- OPERATIONS ---
    public static final byte OP_GENESIS = 0x00;
    public static final byte OP_SEND = 0x01;
    public static final byte OP_MINT = 0x02;
    public static final byte OP_BURN = 0x03;
    public static final byte OP_RECEIVE = 0x04;
    public static final byte OP_ADD_PEER = 0x05;
    public static final byte OP_GET_PUBKEY = 0x10;
    public static final byte OP_VERIFY_PIN = 0x20;
    public static final byte OP_CHANGE_PIN = 0x21;
    public static final byte OP_GET_CERT = 0x30;
    public static final byte OP_SET_CERT = 0x32;
    public static final byte OP_GET_STATUS = 0x60;

    public static final int CLA_PROPRIETARY = 0x80;

    public enum OperationType {
        GENESIS(Constants.OP_GENESIS, "GENESIS"),
        SEND(Constants.OP_SEND, "SEND"),
        MINT(Constants.OP_MINT, "MINT"),
        BURN(Constants.OP_BURN, "BURN");

        public final byte code;
        public final String name;

        OperationType(byte code, String name) {
            this.code = code;
            this.name = name;
        }

        public static OperationType fromCode(byte code) {
            for (OperationType type : values()) {
                if (type.code == code) return type;
            }
            throw new IllegalArgumentException("Unknown code: " + code);
        }

        public static OperationType fromString(String name) {
            for (OperationType type : values()) {
                if (type.name.equalsIgnoreCase(name)) return type;
            }
            throw new IllegalArgumentException("Unknown type name: " + name);
        }

        public static boolean isValidOpCode(byte code) {
            for (OperationType type : values()) {
                if (type.code == code) return true;
            }
            return false;
        }
    }

    // --- HARDWARE LIMITS ---
    public static final short CARD_MAX_PEERS = 200;
    public static final short CARD_PEER_ROW_SIZE = 28;
    public static final short CARD_PEER_OFFSET_ID = 0;
    public static final short CARD_PEER_OFFSET_RECV = 20;
    public static final short CARD_PEER_OFFSET_SENT = 24;
    public static final int CARD_RAM_BUFFER_SIZE = 512;

    // --- APDU ---
    public static final int APDU_MINT_BURN_SIZE = GOC_SIZE;
    public static final int APDU_SEND_SIZE = ID_SIZE + GOC_SIZE;

    // --- STATUS WORDS ---
    public static final short SW_NO_ERROR = (short) 0x9000;
    public static final short SW_CONDITIONS_NOT_SATISFIED = (short) 0x6985;
    public static final short SW_WRONG_DATA = (short) 0x6A80;
    public static final short SW_FILE_FULL = (short) 0x6A84;
    public static final short SW_SECURITY_STATUS_NOT_SATISFIED = (short) 0x6982;
    public static final short SW_UNKNOWN = (short) 0x6F00;
}