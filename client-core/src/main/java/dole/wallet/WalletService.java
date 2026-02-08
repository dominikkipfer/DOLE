package dole.wallet;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import dole.Constants;
import dole.card.SmartCard;
import dole.crypto.CryptoUtils;
import dole.crypto.ProtocolSerializer;
import dole.balance.BalanceResult;
import dole.ledger.LedgerService;
import dole.balance.TransactionDelta;
import dole.ledger.Ledger;
import dole.ledger.LedgerEntry;
import dole.transaction.BurnTransaction;
import dole.transaction.MintTransaction;
import dole.transaction.SendTransaction;
import dole.transaction.Transaction;

public class WalletService implements Closeable {
    private final SmartCard card;
    private final SettingsService settingsService;
    private final LedgerService ledgerService;
    private final Ledger ledger;
    private final byte[] pinBytes;

    private final String ownID;
    private byte[] myPublicKeyBytes;

    private final Map<String, LedgerEntry> knownGenesisLogs = new ConcurrentHashMap<>();

    private final Set<String> processedTxIds = Collections.synchronizedSet(new HashSet<>());
    private boolean isFirstLoad = true;

    private Consumer<WalletState> onStateUpdateListener;

    public record WalletState(
            int balance,
            List<TransactionDelta> fullHistory,
            List<TransactionDelta> recentlySynced,
            List<Transaction> unsyncedIncoming,
            Set<String> knownPeers,
            long unsyncedPendingSum
    ) {}

    public WalletService(
            SmartCard card,
            char[] userPin,
            SettingsService settingsService,
            LedgerService ledgerService,
            Ledger ledger)throws Exception {
        this.card = card;
        this.settingsService = settingsService;
        this.ledgerService = ledgerService;
        this.ledger = ledger;
        this.pinBytes = ProtocolSerializer.validateAndConvertPin(userPin);
        if (!card.verifyPin(this.pinBytes)) throw new Exception("Start failed: Invalid PIN");

        this.myPublicKeyBytes = card.getPublicKey();
        this.ownID = CryptoUtils.getPersonIdAsHex(myPublicKeyBytes);
    }

    public void setOnStateUpdateListener(Consumer<WalletState> listener) {
        this.onStateUpdateListener = listener;
    }

    public void start() {
        ledgerService.observeRelevantLogs(ownID, this::onLedgerUpdate);
    }

    private void onLedgerUpdate(List<LedgerEntry> allLogs) {
        for (LedgerEntry log : allLogs) {
            if (log.seq == 0) knownGenesisLogs.put(log.getAuthorID(), log);
        }

        ledger.processLogBatch(allLogs, ownID);

        List<Transaction> allTransactions = allLogs.stream()
                                                   .map(this::mapLogToTransaction)
                                                   .filter(Objects::nonNull)
                                                   .collect(Collectors.toList());

        BalanceResult balanceResult = ledgerService.calculateBalance(allTransactions, ownID);
        List<TransactionDelta> fullHistory = balanceResult.items();

        List<TransactionDelta> newItems = new ArrayList<>();

        synchronized (processedTxIds) {
            for (TransactionDelta item : fullHistory) {
                String txId = item.tx().id();
                if (!processedTxIds.contains(txId)) {
                    processedTxIds.add(txId);
                    if (!isFirstLoad) newItems.add(item);
                }
            }
            isFirstLoad = false;
        }

        Map<String, Integer> lastReceivedGocs = settingsService.getLastReceivedGocs(ownID);
        List<LedgerEntry> potentialUnsynced = new ArrayList<>();

        for (LedgerEntry log : allLogs) {
            if (log.type == Constants.OperationType.SEND.code && ownID.equals(log.getTargetID())) {
                int lastKnown = lastReceivedGocs.getOrDefault(log.getAuthorID(), -1);
                if (log.goc > lastKnown) potentialUnsynced.add(log);
            }
        }

        if (!potentialUnsynced.isEmpty()) syncCard(potentialUnsynced);

        Map<String, Integer> updatedGocs = settingsService.getLastReceivedGocs(ownID);
        List<Transaction> trulyUnsyncedTxs = new ArrayList<>();
        long unsyncedSum = 0;

        for (LedgerEntry log : potentialUnsynced) {
            int currentKnown = updatedGocs.getOrDefault(log.getAuthorID(), -1);
            if (log.goc > currentKnown) {
                String txId = CryptoUtils.bytesToHex(log.getHash());
                long delta = balanceResult.items().stream()
                                          .filter(item -> item.tx().id().equals(txId))
                                          .findFirst()
                                          .map(TransactionDelta::delta)
                                          .orElse(0L);

                Transaction tx = mapLogToTransaction(log);
                if (tx != null) {
                    trulyUnsyncedTxs.add(tx);
                    unsyncedSum += delta;
                }
            }
        }

        if (onStateUpdateListener != null) {
            onStateUpdateListener.accept(new WalletState(
                    balanceResult.totalBalance(),
                    fullHistory,
                    newItems,
                    trulyUnsyncedTxs,
                    knownGenesisLogs.keySet(),
                    unsyncedSum
            ));
        }
    }

    private void syncCard(List<LedgerEntry> entriesToSync) {
        Map<String, Integer> currentGocs = settingsService.getLastReceivedGocs(ownID);
        for (LedgerEntry log : entriesToSync) {
            try {
                applyLogToCard(log);
                currentGocs.put(log.getAuthorID(), log.goc);
            } catch (Exception e) {
                System.err.println("Auto-Sync failed for log " + log.seq + ": " + e.getMessage());
            }
        }
    }

    private void applyLogToCard(LedgerEntry log) throws Exception {
        String authorId = log.getAuthorID();
        ensurePeerRegistered(authorId);

        byte[] senderRawKey = null;
        LedgerEntry genesis = knownGenesisLogs.get(authorId);
        if (genesis != null) {
            senderRawKey = genesis.getAttachmentPublicKey();
        } else {
            java.security.PublicKey cachedKey = ledger.getPublicKey(authorId);
            if (cachedKey != null) senderRawKey = cachedKey.getEncoded();
        }

        if (senderRawKey == null) throw new IllegalStateException("Key missing for " + authorId);

        byte[] payload = ProtocolSerializer.buildReceivePayload(
                pinBytes,
                senderRawKey,
                log.getSignature(),
                log.getPayloadBytes()
        );

        card.processReceive(payload);
        settingsService.updateLastReceivedGoc(this.ownID, authorId, log.goc);
    }

    private void ensurePeerRegistered(String peerID) throws Exception {
        LedgerEntry genesis = knownGenesisLogs.get(peerID);
        if (genesis != null && genesis.getAttachmentCertificate() != null) {
            try {
                card.addPeer(ProtocolSerializer.buildAddPeerPayload(
                        genesis.getAttachmentCertificate(),
                        genesis.getAttachmentPublicKey()
                ));
            } catch (Exception ignored) {}
        } else {
            if (ledger.getPublicKey(peerID) == null) {
                throw new Exception("Cannot register peer: Genesis cert missing for " + peerID);
            }
        }
    }

    public Transaction mint(int amount) throws Exception {
        return executeTx(ProtocolSerializer.buildMintBurnPayload(pinBytes, amount), card::processMint);
    }

    public Transaction burn(int amount) throws Exception {
        return executeTx(ProtocolSerializer.buildMintBurnPayload(pinBytes, amount), card::processBurn);
    }

    public Transaction send(String receiverID, int amount) throws Exception {
        try { ensurePeerRegistered(receiverID); } catch (Exception ignored) {}
        return executeTx(ProtocolSerializer.buildSendPayload(pinBytes, receiverID, amount), card::processSend);
    }

    public void initGenesisTx() throws Exception {
        if (myPublicKeyBytes == null) myPublicKeyBytes = card.getPublicKey();
        byte[] cert = card.getCertificate();
        byte[] response = card.processGenesis();

        LedgerEntry entry = LedgerEntry.fromCardResponse(response);
        entry.setAttachments(cert, myPublicKeyBytes);
        ledgerService.saveEntry(entry);
        knownGenesisLogs.put(ownID, entry);
        ledgerService.observeRelevantLogs(ownID, this::onLedgerUpdate);
    }

    private Transaction executeTx(byte[] payload, CardOperation op) throws Exception {
        byte[] response = op.execute(payload);
        LedgerEntry entry = LedgerEntry.fromCardResponse(response);
        ledgerService.saveEntry(entry);
        return mapLogToTransaction(entry);
    }

    private Transaction mapLogToTransaction(LedgerEntry entry) {
        var type = Constants.OperationType.fromCode(entry.type);
        String id = CryptoUtils.bytesToHex(entry.getHash());
        int seq = entry.seq;
        String auth = entry.getAuthorID();
        long goc = entry.goc;

        return switch (type) {
            case GENESIS -> null;
            case MINT -> new MintTransaction(id, seq, auth, goc);
            case BURN -> new BurnTransaction(id, seq, auth, goc);
            case SEND -> new SendTransaction(id, seq, auth, entry.getTargetID(), goc);
        };
    }

    @Override
    public void close() {
        if (settingsService != null) settingsService.persist();
    }

    @FunctionalInterface
    interface CardOperation {
        byte[] execute(byte[] input) throws Exception;
    }
}