package card;

import javacard.framework.APDU;
import javacard.framework.Applet;
import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.JCSystem;
import javacard.framework.OwnerPIN;
import javacard.framework.Util;
import javacard.security.CryptoException;
import javacard.security.ECKey;
import javacard.security.ECPrivateKey;
import javacard.security.ECPublicKey;
import javacard.security.KeyBuilder;
import javacard.security.KeyPair;
import javacard.security.MessageDigest;
import javacard.security.Signature;
import dole.Constants;

public class Card extends Applet {

    // --- ROOT CA PUBLIC KEY ---
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

    // --- SECP256R1 (NIST P-256) CONSTANTS ---
    private static final byte[] SECP256R1_P = {
            (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x01,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF,
            (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF
    };
    private static final byte[] SECP256R1_A = {
            (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x01,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF,
            (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFC
    };
    private static final byte[] SECP256R1_B = {
            (byte)0x5A, (byte)0xC6, (byte)0x35, (byte)0xD8, (byte)0xAA, (byte)0x3A, (byte)0x93, (byte)0xE7,
            (byte)0xB3, (byte)0xEB, (byte)0xBD, (byte)0x55, (byte)0x76, (byte)0x98, (byte)0x86, (byte)0xBC,
            (byte)0x65, (byte)0x1D, (byte)0x06, (byte)0xB0, (byte)0xCC, (byte)0x53, (byte)0xB0, (byte)0xF6,
            (byte)0x3B, (byte)0xCE, (byte)0x3C, (byte)0x3E, (byte)0x27, (byte)0xD2, (byte)0x60, (byte)0x4B
    };
    private static final byte[] SECP256R1_G = {
            (byte)0x04,
            (byte)0x6B, (byte)0x17, (byte)0xD1, (byte)0xF2, (byte)0xE1, (byte)0x2C, (byte)0x42, (byte)0x47,
            (byte)0xF8, (byte)0xBC, (byte)0xE6, (byte)0xE5, (byte)0x63, (byte)0xA4, (byte)0x40, (byte)0xF2,
            (byte)0x77, (byte)0x03, (byte)0x7D, (byte)0x81, (byte)0x2D, (byte)0xEB, (byte)0x33, (byte)0xA0,
            (byte)0xF4, (byte)0xA1, (byte)0x39, (byte)0x45, (byte)0xD8, (byte)0x98, (byte)0xC2, (byte)0x96,
            (byte)0x4F, (byte)0xE3, (byte)0x42, (byte)0xE2, (byte)0xFE, (byte)0x1A, (byte)0x7F, (byte)0x9B,
            (byte)0x8E, (byte)0xE7, (byte)0xEB, (byte)0x4A, (byte)0x7C, (byte)0x0F, (byte)0x9E, (byte)0x16,
            (byte)0x2B, (byte)0xCE, (byte)0x33, (byte)0x57, (byte)0x6B, (byte)0x31, (byte)0x5E, (byte)0xCE,
            (byte)0xCB, (byte)0xB6, (byte)0x40, (byte)0x68, (byte)0x37, (byte)0xBF, (byte)0x51, (byte)0xF5
    };
    private static final byte[] SECP256R1_R = {
            (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF,
            (byte)0xBC, (byte)0xE6, (byte)0xFA, (byte)0xAD, (byte)0xA7, (byte)0x17, (byte)0x9E, (byte)0x84,
            (byte)0xF3, (byte)0xB9, (byte)0xCA, (byte)0xC2, (byte)0xFC, (byte)0x63, (byte)0x25, (byte)0x51
    };
    private static final byte k = 1;

    // --- STATE ---
    private final OwnerPIN ownerPin;
    private final boolean isMinter;
    private boolean setupDone = false;
    private boolean isPinSet = false;
    private boolean certificateSet = false;
    private boolean genesisDone = false;

    // Persistent Storage
    private final byte[] seqNumber;
    private final byte[] lastSignedHash;
    private final byte[] totalCreated;
    private final byte[] totalBurned;
    private final byte[] totalReceived;
    private final byte[] totalSent;
    private final byte[] peerData;
    private final byte[] myId;
    private final byte[] deviceCertificate;

    private short peerCount = 0;
    private short certLength = 0;

    // Crypto Objects
    private final KeyPair keyPair;
    private final ECPrivateKey myPrivateKey;
    private final ECPublicKey myPublicKey;
    private final Signature signer;
    private final Signature verifier;
    private final MessageDigest hasher;
    private final ECPublicKey guestKey;
    private final ECPublicKey trustedRootCA;

    // Transient RAM
    private final byte[] ramBuffer;
    private final byte[] mathA;
    private final byte[] mathB;
    private final byte[] mathRes;
    private final byte[] mathT1;
    private final byte[] mathT2;
    private final short[] parseResult;

    /**
     * Installs the applet.
     */
    public static void install(byte[] bArray, short bOffset, byte bLength) {
        new Card(bArray, bOffset);
    }

    /**
     * Applet constructor. Initializes memory and crypto.
     */
    protected Card(byte[] bArray, short bOffset) {
        ownerPin = new OwnerPIN((byte) 3, Constants.PIN_SIZE);

        byte aidLen = bArray[bOffset];
        short privateOffset = (short)(bOffset + aidLen + 1);
        byte privateLen = bArray[privateOffset];
        short paramLenOffset = (short)(privateOffset + privateLen + 1);
        byte paramLen = bArray[paramLenOffset];
        short paramDataOffset = (short)(paramLenOffset + 1);

        if (paramLen > 0) {
            byte minterByte = bArray[paramDataOffset];
            this.isMinter = (minterByte == (byte) 0x01);
        } else {
            this.isMinter = false;
        }

        seqNumber = new byte[Constants.GOC_SIZE];
        lastSignedHash = new byte[Constants.HASH_SIZE];
        totalCreated = new byte[Constants.GOC_SIZE];
        totalBurned = new byte[Constants.GOC_SIZE];
        totalReceived = new byte[Constants.GOC_SIZE];
        totalSent = new byte[Constants.GOC_SIZE];
        myId = new byte[Constants.ID_SIZE];
        deviceCertificate = new byte[512];
        peerData = new byte[Constants.CARD_MAX_PEERS * Constants.CARD_PEER_ROW_SIZE];

        ramBuffer = JCSystem.makeTransientByteArray(Constants.CARD_RAM_BUFFER_SIZE, JCSystem.CLEAR_ON_DESELECT);
        mathA = JCSystem.makeTransientByteArray(Constants.GOC_SIZE, JCSystem.CLEAR_ON_DESELECT);
        mathB = JCSystem.makeTransientByteArray(Constants.GOC_SIZE, JCSystem.CLEAR_ON_DESELECT);
        mathRes = JCSystem.makeTransientByteArray(Constants.GOC_SIZE, JCSystem.CLEAR_ON_DESELECT);
        mathT1 = JCSystem.makeTransientByteArray(Constants.GOC_SIZE, JCSystem.CLEAR_ON_DESELECT);
        mathT2 = JCSystem.makeTransientByteArray(Constants.GOC_SIZE, JCSystem.CLEAR_ON_DESELECT);
        parseResult = JCSystem.makeTransientShortArray((short) 2, JCSystem.CLEAR_ON_DESELECT);

        try {
            keyPair = new KeyPair(KeyPair.ALG_EC_FP, KeyBuilder.LENGTH_EC_FP_256);
            myPrivateKey = (ECPrivateKey) keyPair.getPrivate();
            myPublicKey = (ECPublicKey) keyPair.getPublic();

            initCurve(myPrivateKey);
            initCurve(myPublicKey);

            guestKey = (ECPublicKey) KeyBuilder.buildKey(KeyBuilder.TYPE_EC_FP_PUBLIC, KeyBuilder.LENGTH_EC_FP_256, false);
            initCurve(guestKey);

            trustedRootCA = (ECPublicKey) KeyBuilder.buildKey(KeyBuilder.TYPE_EC_FP_PUBLIC, KeyBuilder.LENGTH_EC_FP_256, false);
            initCurve(trustedRootCA);
            trustedRootCA.setW(ROOT_CA_BYTES, (short) 0, (short) ROOT_CA_BYTES.length);

            signer = Signature.getInstance(Signature.ALG_ECDSA_SHA_256, false);
            verifier = Signature.getInstance(Signature.ALG_ECDSA_SHA_256, false);
            hasher = MessageDigest.getInstance(MessageDigest.ALG_SHA_256, false);
        } catch (CryptoException e) {
            ISOException.throwIt(ISO7816.SW_FUNC_NOT_SUPPORTED);
            throw e;
        }

        register();
    }

    /**
     * Configures the EC Curve parameters.
     */
    private void initCurve(ECKey key) {
        key.setFieldFP(SECP256R1_P, (short)0, (short)SECP256R1_P.length);
        key.setA(SECP256R1_A, (short)0, (short)SECP256R1_A.length);
        key.setB(SECP256R1_B, (short)0, (short)SECP256R1_B.length);
        key.setG(SECP256R1_G, (short)0, (short)SECP256R1_G.length);
        key.setR(SECP256R1_R, (short)0, (short)SECP256R1_R.length);
        key.setK(k);
    }

    /**
     * Main APDU processing loop.
     */
    public void process(APDU apdu) {
        if (selectingApplet()) {
            return;
        }

        if (!setupDone) {
            doLazySetup();
            setupDone = true;
        }

        byte[] buffer = apdu.getBuffer();
        byte ins = buffer[ISO7816.OFFSET_INS];

        try {
            switch (ins) {
                case Constants.OP_GET_STATUS:
                    processGetStatus(apdu);
                    break;
                case Constants.OP_VERIFY_PIN:
                    verifyPin(apdu);
                    break;
                case Constants.OP_CHANGE_PIN:
                    processChangePin(apdu);
                    break;
                case Constants.OP_GENESIS:
                    processGenesis(apdu);
                    break;
                case Constants.OP_SEND:
                    processSend(apdu);
                    break;
                case Constants.OP_RECEIVE:
                    processReceive(apdu);
                    break;
                case Constants.OP_ADD_PEER:
                    addPeer(apdu);
                    break;
                case Constants.OP_MINT:
                    processMint(apdu);
                    break;
                case Constants.OP_BURN:
                    processBurn(apdu);
                    break;
                case Constants.OP_GET_PUBKEY:
                    getPublicKey(apdu);
                    break;
                case Constants.OP_GET_CERT:
                    processGetCert(apdu);
                    break;
                case Constants.OP_SET_CERT:
                    processSetCert(apdu);
                    break;
                default:
                    ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
            }
        } catch (ISOException e) {
            throw e;
        } catch (Exception e) {
            ISOException.throwIt(ISO7816.SW_UNKNOWN);
        }
    }

    /**
     * Lazy initialization of keys and ID on first use.
     */
    private void doLazySetup() {
        if (!myPrivateKey.isInitialized()) keyPair.genKeyPair();

        JCSystem.beginTransaction();
        try {
            short kLen = myPublicKey.getW(ramBuffer, (short)0);
            hasher.doFinal(ramBuffer, (short)0, kLen, ramBuffer, (short)70);
            Util.arrayCopyNonAtomic(ramBuffer, (short)70, myId, (short)0, Constants.ID_SIZE);
        } catch (Exception e) {
            JCSystem.abortTransaction();
            ISOException.throwIt(Constants.SW_UNKNOWN);
        }
        JCSystem.commitTransaction();
    }

    /**
     * Processes the GET STATUS command.
     */
    private void processGetStatus(APDU apdu) {
        byte[] buffer = apdu.getBuffer();
        buffer[0] = isMinter ? (byte) 0x01 : (byte) 0x00;
        buffer[1] = isPinSet ? (byte) 0x01 : (byte) 0x00;
        buffer[2] = genesisDone ? (byte) 0x01 : (byte) 0x00;
        buffer[3] = ownerPin.getTriesRemaining();
        apdu.setOutgoingAndSend((short)0, (short)4);
    }

    /**
     * Verifies the user PIN.
     */
    private void verifyPin(APDU apdu) {
        if (!isPinSet) ISOException.throwIt(Constants.SW_CONDITIONS_NOT_SATISFIED);

        byte[] buffer = apdu.getBuffer();
        short len = apdu.setIncomingAndReceive();
        if (len != Constants.PIN_SIZE) ISOException.throwIt(Constants.SW_WRONG_DATA);

        if (!ownerPin.check(buffer, ISO7816.OFFSET_CDATA, Constants.PIN_SIZE)) {
            ISOException.throwIt(Constants.SW_SECURITY_STATUS_NOT_SATISFIED);
        }
    }

    /**
     * Changes the user PIN.
     */
    private void processChangePin(APDU apdu) {
        byte[] buffer = apdu.getBuffer();
        short len = apdu.setIncomingAndReceive();

        if (len != Constants.PIN_SIZE) ISOException.throwIt(Constants.SW_WRONG_DATA);
        if (isPinSet) {
            if (!ownerPin.isValidated()) ISOException.throwIt(Constants.SW_SECURITY_STATUS_NOT_SATISFIED);
        }

        ownerPin.update(buffer, ISO7816.OFFSET_CDATA, Constants.PIN_SIZE);
        isPinSet = true;
    }

    /**
     * Checks if PIN is currently validated.
     */
    private void checkPin() {
        if (!isPinSet || !ownerPin.isValidated()) ISOException.throwIt(Constants.SW_SECURITY_STATUS_NOT_SATISFIED);
    }

    /**
     * Sends the public key to host.
     */
    private void getPublicKey(APDU apdu) {
        byte[] buffer = apdu.getBuffer();
        short len = myPublicKey.getW(buffer, (short)0);
        apdu.setOutgoingAndSend((short)0, len);
    }

    /**
     * Helper to read transaction amount into mathA buffer.
     */
    private void readAmountToMathA(APDU apdu) {
        byte[] buffer = apdu.getBuffer();
        short len = apdu.setIncomingAndReceive();

        if (len < Constants.GOC_SIZE) ISOException.throwIt(Constants.SW_WRONG_DATA);

        Util.arrayCopyNonAtomic(buffer, ISO7816.OFFSET_CDATA, mathA, (short)0, Constants.GOC_SIZE);

        if (MathLib.isZero(mathA)) ISOException.throwIt(Constants.SW_WRONG_DATA);
    }

    private void checkGenesisDone() {
        if (!genesisDone) {
            ISOException.throwIt(Constants.SW_CONDITIONS_NOT_SATISFIED);
        }
    }

    /**
     * Creates the genesis block (Initial Supply).
     */
    private void processGenesis(APDU apdu) {
        checkPin();
        if (genesisDone) ISOException.throwIt(Constants.SW_CONDITIONS_NOT_SATISFIED);

        JCSystem.beginTransaction();
        genesisDone = true;
        short len = buildLogAndSign(Constants.OP_GENESIS, null, (short)0, null);
        JCSystem.commitTransaction();

        sendRamResponse(apdu, len);
    }

    /**
     * Mints new currency (Minter only).
     */
    private void processMint(APDU apdu) {
        checkPin();
        checkGenesisDone();
        if (!isMinter) ISOException.throwIt(Constants.SW_SECURITY_STATUS_NOT_SATISFIED);

        readAmountToMathA(apdu);

        if (mathA[0] < 0) ISOException.throwIt(ISO7816.SW_DATA_INVALID);
        if (MathLib.isZero(mathA)) ISOException.throwIt(ISO7816.SW_DATA_INVALID);
        if (!MathLib.addSafe(totalCreated, mathA, mathRes)) ISOException.throwIt(Constants.SW_FILE_FULL);
        commitAndLog(apdu, Constants.OP_MINT, mathRes);
    }

    /**
     * Burns currency (removes from circulation).
     */
    private void processBurn(APDU apdu) {
        checkPin();
        checkGenesisDone();

        readAmountToMathA(apdu);

        if (mathA[0] < 0) ISOException.throwIt(ISO7816.SW_DATA_INVALID);
        if (MathLib.isZero(mathA)) ISOException.throwIt(ISO7816.SW_DATA_INVALID);
        calcBalance(totalCreated, totalReceived, totalBurned, totalSent, mathRes);
        if (MathLib.compare(mathRes, mathA) < 0) ISOException.throwIt(Constants.SW_CONDITIONS_NOT_SATISFIED);
        if (!MathLib.addSafe(totalBurned, mathA, mathRes)) ISOException.throwIt(Constants.SW_FILE_FULL);
        commitAndLog(apdu, Constants.OP_BURN, mathRes);
    }

    /**
     * Sends currency to another peer.
     */
    private void processSend(APDU apdu) {
        checkPin();
        checkGenesisDone();

        byte[] buffer = apdu.getBuffer();
        short len = apdu.setIncomingAndReceive();
        if (len < (short)(Constants.ID_SIZE + Constants.GOC_SIZE)) ISOException.throwIt(Constants.SW_WRONG_DATA);

        short off = ISO7816.OFFSET_CDATA;
        short targetIdOff = off;
        off += Constants.ID_SIZE;

        Util.arrayCopyNonAtomic(buffer, off, mathA, (short)0, Constants.GOC_SIZE);
        if (mathA[0] < 0) ISOException.throwIt(ISO7816.SW_DATA_INVALID);
        if (Util.arrayCompare(buffer, targetIdOff, myId, (short)0, Constants.ID_SIZE) == 0) ISOException.throwIt(Constants.SW_WRONG_DATA);
        if (MathLib.isZero(mathA)) ISOException.throwIt(Constants.SW_WRONG_DATA);

        calcBalance(totalCreated, totalReceived, totalBurned, totalSent, mathRes);
        if (MathLib.compare(mathRes, mathA) < 0) ISOException.throwIt(Constants.SW_CONDITIONS_NOT_SATISFIED);

        short peerIdx = findPeer(buffer, targetIdOff);
        if (peerIdx == -1) ISOException.throwIt(Constants.SW_CONDITIONS_NOT_SATISFIED);
        if (!MathLib.addSafe(totalSent, mathA, mathRes)) ISOException.throwIt(Constants.SW_FILE_FULL);
        short peerSentOff = getPeerOffset(peerIdx, Constants.CARD_PEER_OFFSET_SENT);
        Util.arrayCopyNonAtomic(peerData, peerSentOff, mathB, (short)0, Constants.GOC_SIZE);

        if (!MathLib.addSafe(mathB, mathA, mathB)) ISOException.throwIt(Constants.SW_FILE_FULL);

        JCSystem.beginTransaction();
        Util.arrayCopyNonAtomic(mathRes, (short)0, totalSent, (short)0, Constants.GOC_SIZE);
        Util.arrayCopyNonAtomic(mathB, (short)0, peerData, peerSentOff, Constants.GOC_SIZE);
        short logLen = buildLogAndSign(Constants.OP_SEND, buffer, targetIdOff, mathB);
        JCSystem.commitTransaction();

        sendRamResponse(apdu, logLen);
    }

    /**
     * Receives transaction proof from another peer.
     */
    public void processReceive(APDU apdu) {
        checkPin();
        checkGenesisDone();

        byte[] buffer = apdu.getBuffer();
        short len = apdu.setIncomingAndReceive();
        short off = ISO7816.OFFSET_CDATA;

        off = parseField(buffer, off);
        short keyOff = parseResult[0];
        short keyLen = parseResult[1];

        off = parseField(buffer, off);
        short sigOff = parseResult[0];
        short sigLen = parseResult[1];

        short logOff = off;
        if ((short)(len - (logOff - ISO7816.OFFSET_CDATA)) < Constants.LOG_PAYLOAD_SIZE) ISOException.throwIt(Constants.SW_WRONG_DATA);
        short senderIdScratch = 100;
        calculatePeerIdFromBytes(buffer, keyOff, keyLen, ramBuffer, senderIdScratch);
        short peerIdx = findPeer(ramBuffer, senderIdScratch);
        if (peerIdx == -1) ISOException.throwIt(Constants.SW_SECURITY_STATUS_NOT_SATISFIED);
        if (buffer[(short)(logOff + Constants.LOG_OFFSET_TYPE)] != Constants.OP_SEND) ISOException.throwIt(Constants.SW_WRONG_DATA);
        if (Util.arrayCompare(buffer, (short)(logOff + Constants.LOG_OFFSET_TARGET), myId, (short)0, Constants.ID_SIZE) != 0) {
            ISOException.throwIt(Constants.SW_WRONG_DATA);
        }
        if (Util.arrayCompare(buffer, (short)(logOff + Constants.LOG_OFFSET_AUTHOR), ramBuffer, senderIdScratch, Constants.ID_SIZE) != 0) {
            ISOException.throwIt(Constants.SW_WRONG_DATA);
        }
        Util.arrayCopyNonAtomic(buffer, (short)(logOff + Constants.LOG_OFFSET_GOC), mathA, (short)0, Constants.GOC_SIZE);

        if (mathA[0] < 0) ISOException.throwIt(ISO7816.SW_DATA_INVALID);
        short peerRecvOff = getPeerOffset(peerIdx, Constants.CARD_PEER_OFFSET_RECV);
        Util.arrayCopyNonAtomic(peerData, peerRecvOff, mathB, (short)0, Constants.GOC_SIZE);
        if (MathLib.compare(mathA, mathB) <= 0) ISOException.throwIt(Constants.SW_CONDITIONS_NOT_SATISFIED);
        if (!verifySig(buffer, keyOff, keyLen, logOff, sigOff, sigLen)) {
            ISOException.throwIt(Constants.SW_SECURITY_STATUS_NOT_SATISFIED);
        }
        MathLib.subtract(mathA, mathB, mathRes);
        if (!MathLib.addSafe(totalReceived, mathRes, mathB)) ISOException.throwIt(Constants.SW_FILE_FULL);

        JCSystem.beginTransaction();
        Util.arrayCopyNonAtomic(mathB, (short)0, totalReceived, (short)0, Constants.GOC_SIZE);
        Util.arrayCopyNonAtomic(mathA, (short)0, peerData, peerRecvOff, Constants.GOC_SIZE);
        JCSystem.commitTransaction();
    }

    /**
     * Adds a new peer to the local directory.
     */
    public void addPeer(APDU apdu) {
        checkPin();
        byte[] buffer = apdu.getBuffer();
        short len = apdu.setIncomingAndReceive();
        if (len < 6) ISOException.throwIt(Constants.SW_WRONG_DATA);

        short off = ISO7816.OFFSET_CDATA;

        off = parseField(buffer, off);
        short certOff = parseResult[0];
        short certLen = parseResult[1];

        parseField(buffer, off);
        short keyOff = parseResult[0];
        short keyLen = parseResult[1];
        short idScratch = 100;
        calculatePeerIdFromBytes(buffer, keyOff, keyLen, ramBuffer, idScratch);
        if (findPeer(ramBuffer, idScratch) != -1) return;

        if (!verifyCertRaw(buffer, keyOff, keyLen, certOff, certLen)) {
            ISOException.throwIt(Constants.SW_SECURITY_STATUS_NOT_SATISFIED);
        }

        if (peerCount >= Constants.CARD_MAX_PEERS) ISOException.throwIt(Constants.SW_FILE_FULL);

        JCSystem.beginTransaction();
        short peerStart = getPeerOffset(peerCount, Constants.CARD_PEER_OFFSET_ID);
        Util.arrayCopyNonAtomic(ramBuffer, idScratch, peerData, peerStart, Constants.ID_SIZE);
        peerCount++;
        JCSystem.commitTransaction();
    }

    /**
     * Stores the device certificate.
     */
    private void processSetCert(APDU apdu) {
        if (isPinSet) checkPin();
        if (certificateSet) ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);

        byte[] buffer = apdu.getBuffer();
        short len = apdu.setIncomingAndReceive();
        if (len > (short)deviceCertificate.length) ISOException.throwIt(Constants.SW_FILE_FULL);

        try {
            verifier.init(trustedRootCA, Signature.MODE_VERIFY);
            short myKeyLen = myPublicKey.getW(ramBuffer, (short)0);

            boolean isValid = verifier.verify(ramBuffer, (short)0, myKeyLen, buffer, ISO7816.OFFSET_CDATA, len);
            if (!isValid) ISOException.throwIt(Constants.SW_SECURITY_STATUS_NOT_SATISFIED);
        } catch (CryptoException e) {
            ISOException.throwIt(ISO7816.SW_DATA_INVALID);
        }
        Util.arrayCopyNonAtomic(buffer, ISO7816.OFFSET_CDATA, deviceCertificate, (short)0, len);
        certLength = len;
        certificateSet = true;
    }

    /**
     * Retrieves the device certificate.
     */
    private void processGetCert(APDU apdu) {
        if (certLength == 0) ISOException.throwIt(Constants.SW_CONDITIONS_NOT_SATISFIED);
        byte[] buffer = apdu.getBuffer();
        Util.arrayCopyNonAtomic(deviceCertificate, (short)0, buffer, (short)0, certLength);
        apdu.setOutgoingAndSend((short)0, certLength);
    }

    /**
     * Helper to construct and sign the log entry.
     */
    private short buildLogAndSign(byte type, byte[] targetIdSource, short targetIdOff, byte[] valSource) {
        short off = 0;

        off = Util.arrayCopyNonAtomic(seqNumber, (short)0, ramBuffer, off, Constants.GOC_SIZE);
        off = Util.arrayCopyNonAtomic(lastSignedHash, (short)0, ramBuffer, off, Constants.HASH_SIZE);
        ramBuffer[off++] = type;
        off = Util.arrayCopyNonAtomic(myId, (short)0, ramBuffer, off, Constants.ID_SIZE);

        if (targetIdSource != null) {
            off = Util.arrayCopyNonAtomic(targetIdSource, targetIdOff, ramBuffer, off, Constants.ID_SIZE);
        } else {
            off = Util.arrayFillNonAtomic(ramBuffer, off, Constants.ID_SIZE, (byte)0);
        }

        if (valSource != null) {
            Util.arrayCopyNonAtomic(valSource, (short) 0, ramBuffer, off, Constants.GOC_SIZE);
        } else {
            Util.arrayFillNonAtomic(ramBuffer, off, Constants.GOC_SIZE, (byte) 0);
        }

        hasher.doFinal(ramBuffer, (short)0, Constants.LOG_PAYLOAD_SIZE, lastSignedHash, (short)0);
        MathLib.increment(seqNumber);

        signer.init(myPrivateKey, Signature.MODE_SIGN);
        short sigLen = signer.sign(ramBuffer, (short)0, Constants.LOG_PAYLOAD_SIZE, ramBuffer, Constants.LOG_PAYLOAD_SIZE);

        return (short)(Constants.LOG_PAYLOAD_SIZE + sigLen);
    }

    /**
     * Parses a length-prefixed field.
     */
    private short parseField(byte[] input, short off) {
        short len = Util.getShort(input, off);
        off += 2;
        parseResult[0] = off;
        parseResult[1] = len;
        return (short)(off + len);
    }

    /**
     * Calculates offset for peer data.
     */
    private short getPeerOffset(short idx, short fieldOff) {
        return (short)((idx * Constants.CARD_PEER_ROW_SIZE) + fieldOff);
    }

    /**
     * Finds a peer index by ID.
     */
    private short findPeer(byte[] buf, short off) {
        for (short i=0; i<peerCount; i++) {
            short pOff = getPeerOffset(i, Constants.CARD_PEER_OFFSET_ID);
            if (Util.arrayCompare(peerData, pOff, buf, off, Constants.ID_SIZE) == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Persists changes and generates a log.
     */
    private void commitAndLog(APDU apdu, byte opType, byte[] newValue) {
        JCSystem.beginTransaction();

        if (opType == Constants.OP_MINT) Util.arrayCopyNonAtomic(newValue, (short)0, totalCreated, (short)0, Constants.GOC_SIZE);
        if (opType == Constants.OP_BURN) Util.arrayCopyNonAtomic(newValue, (short)0, totalBurned, (short)0, Constants.GOC_SIZE);

        short len = buildLogAndSign(opType, null, (short)0, newValue);

        JCSystem.commitTransaction();

        sendRamResponse(apdu, len);
    }

    /**
     * Sends RAM buffer content as APDU response.
     */
    private void sendRamResponse(APDU apdu, short len) {
        byte[] buffer = apdu.getBuffer();
        Util.arrayCopyNonAtomic(ramBuffer, (short)0, buffer, (short)0, len);
        apdu.setOutgoingAndSend((short)0, len);
    }

    /**
     * Calculates Balance = (Created + Received) - (Burned + Sent).
     */
    private void calcBalance(byte[] cre, byte[] rec, byte[] burn, byte[] sent, byte[] res) {
        if (!MathLib.addSafe(cre, rec, mathT1)) {
            Util.arrayFillNonAtomic(mathT1, (short)0, Constants.GOC_SIZE, (byte)0xFF);
            mathT1[0] = (byte)0x7F;
        }

        if (!MathLib.addSafe(burn, sent, mathT2)) {
            Util.arrayFillNonAtomic(mathT2, (short)0, Constants.GOC_SIZE, (byte)0xFF);
            mathT2[0] = (byte)0x7F;
        }

        MathLib.subtract(mathT1, mathT2, res);
    }

    /**
     * Calculates the Peer ID (Hash of Public Key).
     */
    private void calculatePeerIdFromBytes(byte[] keyBuf, short keyOff, short keyLen, byte[] destIdBuf, short destIdOff) {
        hasher.doFinal(keyBuf, keyOff, keyLen, ramBuffer, (short)0);
        Util.arrayCopyNonAtomic(ramBuffer, (short)0, destIdBuf, destIdOff, Constants.ID_SIZE);
    }

    /**
     * Verifies a signature.
     */
    private boolean verifySig(byte[] buf, short kOff, short kLen, short dOff, short sOff, short sLen) {
        try {
            guestKey.setW(buf, kOff, kLen);
            verifier.init(guestKey, Signature.MODE_VERIFY);
            return verifier.verify(buf, dOff, Constants.LOG_PAYLOAD_SIZE, buf, sOff, sLen);
        } catch (CryptoException e) {
            return false;
        }
    }

    /**
     * Verifies the device certificate against Root CA.
     */
    private boolean verifyCertRaw(byte[] buf, short kOff, short kLen, short cOff, short cLen) {
        try {
            verifier.init(trustedRootCA, Signature.MODE_VERIFY);
            return verifier.verify(buf, kOff, kLen, buf, cOff, cLen);
        } catch (CryptoException e) {
            return false;
        }
    }
}