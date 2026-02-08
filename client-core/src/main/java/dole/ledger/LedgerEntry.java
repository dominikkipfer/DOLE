package dole.ledger;

import java.security.PublicKey;
import java.util.Arrays;

import dole.Constants;
import dole.crypto.CryptoUtils;
import dole.crypto.ProtocolSerializer;
import org.json.JSONObject;

public class LedgerEntry {

    private final byte[] rawPayload;
    private final byte[] signature;

    public final int seq;
    public final int goc;
    public final byte type;

    private byte[] attachmentPublicKey;
    private byte[] attachmentCertificate;
    private byte[] cachedHash;

    private LedgerEntry(byte[] rawPayload, byte[] signature, int seq, int goc, byte type) {
        this.rawPayload = rawPayload;
        this.signature = signature;
        this.seq = seq;
        this.goc = goc;
        this.type = type;
    }

    public byte[] getAttachmentPublicKey() {
        return attachmentPublicKey;
    }

    public byte[] getAttachmentCertificate() {
        return attachmentCertificate;
    }

    public static LedgerEntry fromCardResponse(byte[] cardOutput) {
        if (cardOutput.length <= Constants.LOG_PAYLOAD_SIZE) {
            throw new IllegalArgumentException("Card output too short");
        }

        byte[] payload = Arrays.copyOfRange(cardOutput, 0, Constants.LOG_PAYLOAD_SIZE);
        byte[] sig = Arrays.copyOfRange(cardOutput, Constants.LOG_PAYLOAD_SIZE, cardOutput.length);

        int seq = ProtocolSerializer.extractSeq(payload);
        byte type = ProtocolSerializer.extractType(payload);
        int goc = ProtocolSerializer.extractGoc(payload);

        return new LedgerEntry(payload, sig, seq, goc, type);
    }

    public static LedgerEntry fromJSON(String json) {
        JSONObject obj = new JSONObject(json);
        return fromJSONObject(obj);
    }

    private static LedgerEntry fromJSONObject(JSONObject obj) {
        int seq = obj.getInt(Constants.KEY_SEQ);
        int goc = obj.getInt(Constants.KEY_GOC);

        byte type = Constants.OperationType.fromString(obj.getString(Constants.KEY_TYPE)).code;

        byte[] payload = ProtocolSerializer.buildLogPayloadFromHex(
                seq,
                obj.getString(Constants.KEY_PREV_HASH),
                type,
                obj.getString(Constants.KEY_AUTHOR),
                obj.getString(Constants.KEY_TARGET),
                goc
        );

        byte[] sigBytes = CryptoUtils.hexToBytes(obj.getString(Constants.KEY_SIGNATURE));

        LedgerEntry entry = new LedgerEntry(payload, sigBytes, seq, goc, type);

        if (obj.has(Constants.KEY_PUBKEY)) {
            entry.attachmentPublicKey = CryptoUtils.hexToBytes(obj.getString(Constants.KEY_PUBKEY));
        }
        if (obj.has(Constants.KEY_CERT)) {
            entry.attachmentCertificate = CryptoUtils.hexToBytes(obj.getString(Constants.KEY_CERT));
        }

        return entry;
    }

    public byte[] getPayloadBytes() {
        return Arrays.copyOf(rawPayload, rawPayload.length);
    }

    public byte[] getSignature() {
        return signature;
    }

    public String getAuthorID() {
        return CryptoUtils.bytesToHex(ProtocolSerializer.extractAuthor(rawPayload));
    }

    public String getTargetID() {
        return CryptoUtils.bytesToHex(ProtocolSerializer.extractTarget(rawPayload));
    }

    public byte[] getPrevHash() {
        return ProtocolSerializer.extractPrevHash(rawPayload);
    }

    public byte[] getHash() {
        if(cachedHash == null) cachedHash = CryptoUtils.sha256(rawPayload);
        return cachedHash;
    }

    public boolean verifyLogSig(PublicKey pk) {
        return CryptoUtils.verifySignature(pk, rawPayload, signature);
    }

    public boolean verifyCertificate(PublicKey rootCA) {
        if (attachmentCertificate == null || attachmentPublicKey == null)
            throw new RuntimeException("Missing Attachment for Verification");

        byte[] calculatedAuthorId = CryptoUtils.calculatePersonId(this.attachmentPublicKey);
        byte[] claimedAuthorId = ProtocolSerializer.extractAuthor(rawPayload);

        if (!Arrays.equals(calculatedAuthorId, claimedAuthorId)) {
            System.err.println("ID mismatch!");
            return false;
        }
        return CryptoUtils.verifySignature(rootCA, attachmentPublicKey, attachmentCertificate);
    }

    public JSONObject toJSONObject() {
        JSONObject json = new JSONObject();
        json.put(Constants.KEY_SEQ, this.seq);
        json.put(Constants.KEY_PREV_HASH, CryptoUtils.bytesToHex(getPrevHash()));
        json.put(Constants.KEY_TYPE, Constants.OperationType.fromCode(this.type).name());
        json.put(Constants.KEY_AUTHOR, getAuthorID());
        json.put(Constants.KEY_TARGET, getTargetID());
        json.put(Constants.KEY_GOC, this.goc);
        json.put(Constants.KEY_SIGNATURE, CryptoUtils.bytesToHex(this.signature));

        if (attachmentPublicKey != null) json.put(Constants.KEY_PUBKEY, CryptoUtils.bytesToHex(attachmentPublicKey));
        if (attachmentCertificate != null) json.put(Constants.KEY_CERT, CryptoUtils.bytesToHex(attachmentCertificate));

        return json;
    }

    public String toJSON() {
        return toJSONObject().toString();
    }

    public void setAttachments(byte[] cert, byte[] pubKey) {
        this.attachmentPublicKey = pubKey;
        this.attachmentCertificate = cert;
    }
}