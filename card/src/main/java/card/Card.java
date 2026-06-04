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
import javacardx.apdu.ExtendedLength;
import dole.Constants;

/**
 * DOLE smart card applet. Manages balance via persistent running counter,
 * stores peers by full 65-byte public key, uses type-specific signed log formats.
 */
public class Card extends Applet implements ExtendedLength {

    // Applet State
    private final OwnerPIN ownerPin;
    private final boolean isMinter;
    private boolean setupDone = false;
    private boolean isPinSet = false;
    private boolean certificateSet = false;
    private boolean genesisDone = false;

    // Persistent Storage
    private final byte[] seqNumber;
    private final byte[] balance;
    private final byte[] totalCreated;
    private final byte[] totalBurned;
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
    private final short[] parseResult;

    private static final short SCRATCH_OFF = 200;
    private static final short RESP_OFF = 300;

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
            this.isMinter = (bArray[paramDataOffset] == (byte) 0x01);
        } else {
            this.isMinter = false;
        }

        seqNumber    = new byte[Constants.LONG_SIZE];
        balance      = new byte[Constants.LONG_SIZE];
        totalCreated = new byte[Constants.LONG_SIZE];
        totalBurned  = new byte[Constants.LONG_SIZE];
        myId         = new byte[Constants.ID_SIZE];
        deviceCertificate = new byte[512];
        peerData     = new byte[Constants.CARD_MAX_PEERS * Constants.CARD_PEER_ROW_SIZE];

        ramBuffer   = JCSystem.makeTransientByteArray(Constants.CARD_RAM_BUFFER_SIZE, JCSystem.CLEAR_ON_DESELECT);
        mathA       = JCSystem.makeTransientByteArray(Constants.LONG_SIZE, JCSystem.CLEAR_ON_DESELECT);
        mathB       = JCSystem.makeTransientByteArray(Constants.LONG_SIZE, JCSystem.CLEAR_ON_DESELECT);
        mathRes     = JCSystem.makeTransientByteArray(Constants.LONG_SIZE, JCSystem.CLEAR_ON_DESELECT);
        parseResult = JCSystem.makeTransientShortArray((short) 2, JCSystem.CLEAR_ON_DESELECT);

        try {
            keyPair = new KeyPair(KeyPair.ALG_EC_FP, KeyBuilder.LENGTH_EC_FP_256);
            myPrivateKey = (ECPrivateKey) keyPair.getPrivate();
            myPublicKey  = (ECPublicKey) keyPair.getPublic();

            initCurve(myPrivateKey);
            initCurve(myPublicKey);

            guestKey = (ECPublicKey) KeyBuilder.buildKey(KeyBuilder.TYPE_EC_FP_PUBLIC, KeyBuilder.LENGTH_EC_FP_256, false);
            initCurve(guestKey);

            trustedRootCA = (ECPublicKey) KeyBuilder.buildKey(KeyBuilder.TYPE_EC_FP_PUBLIC, KeyBuilder.LENGTH_EC_FP_256, false);
            initCurve(trustedRootCA);
            trustedRootCA.setW(CurveConfig.ROOT_CA_BYTES, (short) 0, (short) CurveConfig.ROOT_CA_BYTES.length);

            signer   = Signature.getInstance(Signature.ALG_ECDSA_SHA_256, false);
            verifier = Signature.getInstance(Signature.ALG_ECDSA_SHA_256, false);
            hasher   = MessageDigest.getInstance(MessageDigest.ALG_SHA_256, false);
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
        key.setFieldFP(CurveConfig.SECP256R1_P, (short)0, (short)CurveConfig.SECP256R1_P.length);
        key.setA(CurveConfig.SECP256R1_A, (short)0, (short)CurveConfig.SECP256R1_A.length);
        key.setB(CurveConfig.SECP256R1_B, (short)0, (short)CurveConfig.SECP256R1_B.length);
        key.setG(CurveConfig.SECP256R1_G, (short)0, (short)CurveConfig.SECP256R1_G.length);
        key.setR(CurveConfig.SECP256R1_R, (short)0, (short)CurveConfig.SECP256R1_R.length);
        key.setK(CurveConfig.k);
    }

    /**
     * Main APDU processing loop.
     */
    public void process(APDU apdu) {
        if (selectingApplet()) return;

        if (!setupDone) {
            doLazySetup();
            setupDone = true;
        }

        byte[] buffer = apdu.getBuffer();
        byte ins = buffer[ISO7816.OFFSET_INS];

        try {
            switch (ins) {
                case Constants.OP_GET_STATUS:  processGetStatus(apdu);  break;
                case Constants.OP_VERIFY_PIN:  verifyPin(apdu);         break;
                case Constants.OP_CHANGE_PIN:  processChangePin(apdu);  break;
                case Constants.OP_GENESIS:     processGenesis(apdu);    break;
                case Constants.OP_SEND:        processSend(apdu);       break;
                case Constants.OP_RECEIVE:     processReceive(apdu);    break;
                case Constants.OP_ADD_PEER:    addPeer(apdu);           break;
                case Constants.OP_MINT:        processMint(apdu);       break;
                case Constants.OP_BURN:        processBurn(apdu);       break;
                case Constants.OP_GET_PUBKEY:  getPublicKey(apdu);      break;
                case Constants.OP_GET_CERT:    processGetCert(apdu);    break;
                case Constants.OP_SET_CERT:    processSetCert(apdu);    break;
                default: ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
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
        buffer[0] = isMinter    ? (byte) 0x01 : (byte) 0x00;
        buffer[1] = isPinSet    ? (byte) 0x01 : (byte) 0x00;
        buffer[2] = genesisDone ? (byte) 0x01 : (byte) 0x00;
        buffer[3] = ownerPin.getTriesRemaining();
        Util.arrayCopyNonAtomic(myId, (short)28, buffer, (short)4, (short)4);
        apdu.setOutgoingAndSend((short)0, (short)8);
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
    private void processSetCert(APDU apdu) {
        if (isPinSet) checkPin();
        if (certificateSet) ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);

        byte[] buffer = apdu.getBuffer();
        short len = apdu.setIncomingAndReceive();
        if (len > (short)deviceCertificate.length) ISOException.throwIt(Constants.SW_FILE_FULL);

        try {
            verifier.init(trustedRootCA, Signature.MODE_VERIFY);
            short myKeyLen = myPublicKey.getW(ramBuffer, (short)0);
            if (!verifier.verify(ramBuffer, (short)0, myKeyLen, buffer, ISO7816.OFFSET_CDATA, len)) {
                ISOException.throwIt(Constants.SW_SECURITY_STATUS_NOT_SATISFIED);
            }
        } catch (CryptoException e) {
            ISOException.throwIt(ISO7816.SW_DATA_INVALID);
        }

        Util.arrayCopyNonAtomic(buffer, ISO7816.OFFSET_CDATA, deviceCertificate, (short)0, len);
        certLength = len;
        certificateSet = true;
    }

    private void processGetCert(APDU apdu) {
        if (certLength == 0) ISOException.throwIt(Constants.SW_CONDITIONS_NOT_SATISFIED);
        byte[] buffer = apdu.getBuffer();
        Util.arrayCopyNonAtomic(deviceCertificate, (short)0, buffer, (short)0, certLength);
        apdu.setOutgoingAndSend((short)0, certLength);
    }

    private void checkGenesisDone() {
        if (!genesisDone) ISOException.throwIt(Constants.SW_CONDITIONS_NOT_SATISFIED);
    }

    /**
     * Creates the genesis block (Initial Supply).
     */
    private void processGenesis(APDU apdu) {
        checkPin();
        if (genesisDone) ISOException.throwIt(Constants.SW_CONDITIONS_NOT_SATISFIED);

        JCSystem.beginTransaction();
        genesisDone = true;
        short respLen = signAndBuildResponse(Constants.OP_GENESIS, null, (short)0, Constants.LOG_GENESIS_SIZE);
        JCSystem.commitTransaction();

        sendRamResponse(apdu, respLen);
    }

    /**
     * Mints new currency (Minter only).
     */
    private void processMint(APDU apdu) {
        if (!isMinter) ISOException.throwIt(Constants.SW_SECURITY_STATUS_NOT_SATISFIED);
        processMintOrBurn(apdu, Constants.OP_MINT, totalCreated);
    }

    /**
     * Burns currency (removes from circulation).
     */
    private void processBurn(APDU apdu) {
        processMintOrBurn(apdu, Constants.OP_BURN, totalBurned);
    }

    private void processMintOrBurn(APDU apdu, byte opType, byte[] targetTotalField) {
        checkPin();
        checkGenesisDone();

        byte[] buffer = apdu.getBuffer();
        short len = apdu.setIncomingAndReceive();
        if (len < Constants.LONG_SIZE) ISOException.throwIt(Constants.SW_WRONG_DATA);

        Util.arrayCopyNonAtomic(buffer, ISO7816.OFFSET_CDATA, mathA, (short)0, Constants.LONG_SIZE);
        if (MathLib.isZero(mathA)) ISOException.throwIt(ISO7816.SW_DATA_INVALID);

        if (opType == Constants.OP_MINT) {
            if (!MathLib.add(balance, mathA, mathRes)) ISOException.throwIt(Constants.SW_FILE_FULL);
        } else {
            if (MathLib.compare(balance, mathA) < 0) ISOException.throwIt(Constants.SW_CONDITIONS_NOT_SATISFIED);
            MathLib.subtract(balance, mathA, mathRes);
        }

        if (!MathLib.add(targetTotalField, mathA, mathB)) ISOException.throwIt(Constants.SW_FILE_FULL);

        JCSystem.beginTransaction();
        Util.arrayCopyNonAtomic(mathRes, (short)0, balance, (short)0, Constants.LONG_SIZE);
        Util.arrayCopyNonAtomic(mathB, (short)0, targetTotalField, (short)0, Constants.LONG_SIZE);
        short respLen = signAndBuildResponse(opType, mathB, Constants.LONG_SIZE, Constants.LOG_MINTBURN_SIZE);
        JCSystem.commitTransaction();

        sendRamResponse(apdu, respLen);
    }

    /**
     * Sends currency to another peer.
     */
    private void processSend(APDU apdu) {
        checkPin();
        checkGenesisDone();

        byte[] buffer = apdu.getBuffer();
        short len = apdu.setIncomingAndReceive();
        if (len < Constants.APDU_SEND_SIZE) ISOException.throwIt(Constants.SW_WRONG_DATA);

        short targetPubkeyOff = ISO7816.OFFSET_CDATA;
        short amountOff = (short)(targetPubkeyOff + Constants.PUBKEY_SIZE);

        Util.arrayCopyNonAtomic(buffer, amountOff, mathA, (short)0, Constants.LONG_SIZE);
        if (MathLib.isZero(mathA)) ISOException.throwIt(ISO7816.SW_DATA_INVALID);
        if (MathLib.compare(balance, mathA) < 0) ISOException.throwIt(Constants.SW_CONDITIONS_NOT_SATISFIED);

        short peerIdx = findPeer(buffer, targetPubkeyOff);
        if (peerIdx == -1) ISOException.throwIt(Constants.SW_CONDITIONS_NOT_SATISFIED);

        short peerSentOff = getPeerOffset(peerIdx, Constants.CARD_PEER_OFFSET_SENT);
        Util.arrayCopyNonAtomic(peerData, peerSentOff, mathB, (short)0, Constants.LONG_SIZE);
        if (!MathLib.add(mathB, mathA, mathB)) ISOException.throwIt(Constants.SW_FILE_FULL);

        MathLib.subtract(balance, mathA, mathRes);

        hasher.doFinal(buffer, targetPubkeyOff, Constants.PUBKEY_SIZE, ramBuffer, SCRATCH_OFF);

        JCSystem.beginTransaction();
        Util.arrayCopyNonAtomic(mathRes, (short)0, balance, (short)0, Constants.LONG_SIZE);
        Util.arrayCopyNonAtomic(mathB, (short)0, peerData, peerSentOff, Constants.LONG_SIZE);

        ramBuffer[Constants.LOG_OFFSET_TYPE] = Constants.OP_SEND;
        Util.arrayCopyNonAtomic(myId, (short)0, ramBuffer, Constants.LOG_OFFSET_AUTHOR, Constants.ID_SIZE);
        Util.arrayCopyNonAtomic(seqNumber, (short)0, ramBuffer, Constants.LOG_OFFSET_SEQ, Constants.LONG_SIZE);
        Util.arrayCopyNonAtomic(ramBuffer, SCRATCH_OFF, ramBuffer, Constants.LOG_SEND_OFFSET_TARGET, Constants.ID_SIZE);
        Util.arrayCopyNonAtomic(mathB, (short)0, ramBuffer, Constants.LOG_SEND_OFFSET_GOC, Constants.LONG_SIZE);

        signer.init(myPrivateKey, Signature.MODE_SIGN);
        short sigLen = signer.sign(ramBuffer, (short)0, Constants.LOG_SEND_SIZE, ramBuffer, Constants.LOG_SEND_SIZE);

        Util.arrayCopyNonAtomic(seqNumber, (short)0, ramBuffer, RESP_OFF, Constants.LONG_SIZE);
        Util.arrayCopyNonAtomic(ramBuffer, Constants.LOG_SEND_SIZE, ramBuffer, (short)(RESP_OFF + Constants.LONG_SIZE), sigLen);
        short respLen = (short)(Constants.LONG_SIZE + sigLen);
        Util.arrayCopyNonAtomic(ramBuffer, RESP_OFF, ramBuffer, (short)0, respLen);

        MathLib.increment(seqNumber);
        JCSystem.commitTransaction();

        sendRamResponse(apdu, respLen);
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

        if (keyLen != Constants.PUBKEY_SIZE) ISOException.throwIt(Constants.SW_WRONG_DATA);

        short logOff = off;
        if ((short)(len - (logOff - ISO7816.OFFSET_CDATA)) < Constants.LOG_SEND_SIZE) ISOException.throwIt(Constants.SW_WRONG_DATA);

        if (buffer[logOff] != Constants.OP_SEND) ISOException.throwIt(Constants.SW_WRONG_DATA);

        short peerIdx = findPeer(buffer, keyOff);
        if (peerIdx == -1) ISOException.throwIt(Constants.SW_SECURITY_STATUS_NOT_SATISFIED);

        hasher.doFinal(buffer, keyOff, keyLen, ramBuffer, SCRATCH_OFF);
        if (Util.arrayCompare(buffer, (short)(logOff + Constants.LOG_OFFSET_AUTHOR), ramBuffer, SCRATCH_OFF, Constants.ID_SIZE) != 0) {
            ISOException.throwIt(Constants.SW_WRONG_DATA);
        }

        if (Util.arrayCompare(buffer, (short)(logOff + Constants.LOG_SEND_OFFSET_TARGET), myId, (short)0, Constants.ID_SIZE) != 0) {
            ISOException.throwIt(Constants.SW_WRONG_DATA);
        }

        Util.arrayCopyNonAtomic(buffer, (short)(logOff + Constants.LOG_SEND_OFFSET_GOC), mathA, (short)0, Constants.LONG_SIZE);

        short peerRecvOff = getPeerOffset(peerIdx, Constants.CARD_PEER_OFFSET_RECV);
        Util.arrayCopyNonAtomic(peerData, peerRecvOff, mathB, (short)0, Constants.LONG_SIZE);

        if (MathLib.compare(mathA, mathB) <= 0) ISOException.throwIt(Constants.SW_CONDITIONS_NOT_SATISFIED);

        try {
            guestKey.setW(buffer, keyOff, keyLen);
            verifier.init(guestKey, Signature.MODE_VERIFY);
            if (!verifier.verify(buffer, logOff, Constants.LOG_SEND_SIZE, buffer, sigOff, sigLen)) {
                ISOException.throwIt(Constants.SW_SECURITY_STATUS_NOT_SATISFIED);
            }
        } catch (CryptoException e) {
            ISOException.throwIt(Constants.SW_SECURITY_STATUS_NOT_SATISFIED);
        }

        MathLib.subtract(mathA, mathB, mathRes);
        if (!MathLib.add(balance, mathRes, mathB)) ISOException.throwIt(Constants.SW_FILE_FULL);

        JCSystem.beginTransaction();
        Util.arrayCopyNonAtomic(mathB, (short)0, balance, (short)0, Constants.LONG_SIZE);
        Util.arrayCopyNonAtomic(mathA, (short)0, peerData, peerRecvOff, Constants.LONG_SIZE);
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

        if (keyLen != Constants.PUBKEY_SIZE) ISOException.throwIt(Constants.SW_WRONG_DATA);

        hasher.doFinal(buffer, keyOff, Constants.PUBKEY_SIZE, ramBuffer, (short)0);
        if (Util.arrayCompare(ramBuffer, (short)0, myId, (short)0, Constants.ID_SIZE) == 0) {
            ISOException.throwIt(Constants.SW_WRONG_DATA);
        }

        if (findPeer(buffer, keyOff) != -1) return;

        try {
            verifier.init(trustedRootCA, Signature.MODE_VERIFY);
            if (!verifier.verify(buffer, keyOff, keyLen, buffer, certOff, certLen)) {
                ISOException.throwIt(Constants.SW_SECURITY_STATUS_NOT_SATISFIED);
            }
        } catch (CryptoException e) {
            ISOException.throwIt(Constants.SW_SECURITY_STATUS_NOT_SATISFIED);
        }

        if (peerCount >= Constants.CARD_MAX_PEERS) ISOException.throwIt(Constants.SW_FILE_FULL);

        JCSystem.beginTransaction();
        short peerStart = getPeerOffset(peerCount, Constants.CARD_PEER_OFFSET_KEY);
        Util.arrayCopyNonAtomic(buffer, keyOff, peerData, peerStart, Constants.PUBKEY_SIZE);
        peerCount++;
        JCSystem.commitTransaction();
    }

    /**
     * Retrieves the device certificate.
     */
    private short signAndBuildResponse(byte type, byte[] extraData, short extraLen, short totalLogSize) {
        ramBuffer[Constants.LOG_OFFSET_TYPE] = type;
        Util.arrayCopyNonAtomic(myId, (short)0, ramBuffer, Constants.LOG_OFFSET_AUTHOR, Constants.ID_SIZE);
        Util.arrayCopyNonAtomic(seqNumber, (short)0, ramBuffer, Constants.LOG_OFFSET_SEQ, Constants.LONG_SIZE);

        if (extraData != null && extraLen > 0) {
            Util.arrayCopyNonAtomic(extraData, (short)0, ramBuffer, Constants.LOG_HEADER_SIZE, extraLen);
        }

        signer.init(myPrivateKey, Signature.MODE_SIGN);
        short sigLen = signer.sign(ramBuffer, (short)0, totalLogSize, ramBuffer, totalLogSize);

        Util.arrayCopyNonAtomic(seqNumber, (short)0, ramBuffer, RESP_OFF, Constants.LONG_SIZE);
        Util.arrayCopyNonAtomic(ramBuffer, totalLogSize, ramBuffer, (short)(RESP_OFF + Constants.LONG_SIZE), sigLen);
        short respLen = (short)(Constants.LONG_SIZE + sigLen);

        Util.arrayCopyNonAtomic(ramBuffer, RESP_OFF, ramBuffer, (short)0, respLen);

        MathLib.increment(seqNumber);

        return respLen;
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
     * Finds peer by comparing full 65-byte public keys.
     * @return peer index or -1
     */
    private short findPeer(byte[] buf, short off) {
        for (short i = 0; i < peerCount; i++) {
            short pOff = getPeerOffset(i, Constants.CARD_PEER_OFFSET_KEY);
            if (Util.arrayCompare(peerData, pOff, buf, off, Constants.PUBKEY_SIZE) == 0) return i;
        }
        return -1;
    }

    /**
     * Persists changes and generates a log.
     */
    private void sendRamResponse(APDU apdu, short len) {
        byte[] buffer = apdu.getBuffer();
        Util.arrayCopyNonAtomic(ramBuffer, (short)0, buffer, (short)0, len);
        apdu.setOutgoingAndSend((short)0, len);
    }
}