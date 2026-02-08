package dole.ledger;

import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dole.Constants;
import dole.crypto.CryptoUtils;

public class Ledger {
    private final PublicKey rootCA;
    private final Map<String, PublicKey> keyStore = new ConcurrentHashMap<>();
    private final Map<String, byte[]> hashCache = new ConcurrentHashMap<>();
    private final Map<String, AccountState> accountStates = new ConcurrentHashMap<>();

    public Ledger() {
        this.rootCA = CryptoUtils.decodePublicKey(Constants.ROOT_CA_BYTES);
    }

    public synchronized void initialize(LedgerState state) {
        if (state == null) return;
        this.accountStates.putAll(state.states);
        state.hashes.forEach((k, v) -> this.hashCache.put(k, CryptoUtils.hexToBytes(v)));
        state.keys.forEach((k, v) -> this.keyStore.put(k, CryptoUtils.decodePublicKey(CryptoUtils.hexToBytes(v))));
    }

    public synchronized LedgerState exportState() {
        LedgerState state = new LedgerState();
        state.states.putAll(this.accountStates);
        this.hashCache.forEach((k, v) -> state.hashes.put(k, CryptoUtils.bytesToHex(v)));
        this.keyStore.forEach((k, v) -> state.keys.put(k, CryptoUtils.bytesToHex(v.getEncoded())));
        return state;
    }

    public synchronized List<LedgerEntry> processLogBatch(List<LedgerEntry> batch, String activeUserId) {
        List<LedgerEntry> validLogs = new ArrayList<>();
        batch.sort(Comparator.comparing(LedgerEntry::getAuthorID).thenComparingInt(l -> l.seq));

        for (LedgerEntry log : batch) {
            try {
                if (validate(log, activeUserId)) {
                    apply(log);
                    validLogs.add(log);
                }
            } catch (Exception e) {
                System.out.println("LOG REJECTED: " + e.getMessage());
            }
        }
        return validLogs;
    }

    private boolean validate(LedgerEntry log, String activeUserId) throws Exception {
        String author = log.getAuthorID();
        AccountState state = accountStates.get(author);

        if (state != null && log.seq <= state.lastSeq) return false;

        byte[] expectedHash = hashCache.getOrDefault(author, Constants.ZERO_HASH);
        boolean isMe = author.equals(activeUserId);

        if (!Arrays.equals(log.getPrevHash(), expectedHash)) {
            if (isMe) throw new Exception("My Hash Chain Broken");
        }

        PublicKey key = keyStore.get(author);
        if (key == null) {
            if (log.type != Constants.OperationType.GENESIS.code) throw new Exception("Unknown author (missing Genesis)");
            if (!log.verifyCertificate(rootCA)) throw new Exception("Invalid Certificate");

            key = CryptoUtils.decodePublicKey(log.getAttachmentPublicKey());
            keyStore.put(author, key);
        }

        if (!log.verifyLogSig(key)) throw new Exception("Invalid Log Signature");

        return true;
    }

    private void apply(LedgerEntry log) {
        String author = log.getAuthorID();
        hashCache.put(author, log.getHash());

        AccountState state = accountStates.computeIfAbsent(author, k -> new AccountState());
        state.lastSeq = log.seq;

        var op = Constants.OperationType.fromCode(log.type);
        if (op == Constants.OperationType.MINT) state.totalMinted = Math.max(state.totalMinted, log.goc);
        else if (op == Constants.OperationType.BURN) state.totalBurned = Math.max(state.totalBurned, log.goc);
    }

    public PublicKey getPublicKey(String id) { return keyStore.get(id); }

    public static class LedgerState {
        public Map<String, AccountState> states = new HashMap<>();
        public Map<String, String> hashes = new HashMap<>();
        public Map<String, String> keys = new HashMap<>();
    }

    public static class AccountState {
        public int lastSeq = -1;
        public int totalMinted = 0;
        public int totalBurned = 0;
    }

    public synchronized void removeAccount(String id) {
        accountStates.remove(id);
        hashCache.remove(id);
        keyStore.remove(id);
    }
}