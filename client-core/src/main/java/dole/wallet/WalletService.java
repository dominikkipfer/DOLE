package dole.wallet;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
import dole.ledger.Ledger.AccountState;
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

    private final Set<String> sessionVerifiedNewIds = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> recoveredTxIds = Collections.synchronizedSet(new HashSet<>());

    private List<LedgerEntry> cachedLogs = new ArrayList<>();

    private boolean isFullHistoryMode = false;
    private volatile boolean isClosed = false;

    private int snapshotLastSeq = 0;

    private final Map<String, Integer> snapshotReceivedGocs = new HashMap<>();
    private final Map<String, Integer> snapshotSentGocs = new HashMap<>();
    private int snapshotMintGoc = 0;
    private int snapshotBurnGoc = 0;

    private final Map<String, Integer> startupReceivedGocs = new HashMap<>();
    private final Map<String, Integer> startupSentGocs = new HashMap<>();
    private int startupMintGoc = 0;
    private int startupBurnGoc = 0;

    private Consumer<WalletState> onStateUpdateListener;

    public record WalletState(
            int balance,
            int confirmedBalance,
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

    public void setSearchMode(boolean enabled) {
        if (enabled) {
            this.isFullHistoryMode = true;
            ledgerService.setHistorySyncMode(true);
            onLedgerUpdate(this.cachedLogs);
        } else {
            this.isFullHistoryMode = false;
            ledgerService.setHistorySyncMode(false);
        }
    }

    public void start() {
        this.snapshotLastSeq = settingsService.getLastKnownSeq(ownID);
        this.snapshotReceivedGocs.putAll(settingsService.getLastReceivedGocs(ownID));
        this.snapshotSentGocs.putAll(settingsService.getLastSentGocs(ownID));

        AccountState as = settingsService.getInitialAccountState(ownID);
        this.snapshotMintGoc = as.totalMinted;
        this.snapshotBurnGoc = as.totalBurned;
        this.startupReceivedGocs.putAll(this.snapshotReceivedGocs);
        this.startupSentGocs.putAll(this.snapshotSentGocs);
        this.startupMintGoc = this.snapshotMintGoc;
        this.startupBurnGoc = this.snapshotBurnGoc;

        boolean hasLocalHistory = (snapshotMintGoc > 0 || snapshotBurnGoc > 0 || !snapshotSentGocs.isEmpty() || !snapshotReceivedGocs.isEmpty());
        boolean needAutoResync = (snapshotLastSeq > 0 && !hasLocalHistory);
        int effectiveSeq = needAutoResync ? 0 : snapshotLastSeq;

        ledgerService.setHistorySyncMode(isFullHistoryMode);
        ledgerService.observeRelevantLogs(ownID, effectiveSeq, snapshotReceivedGocs, this::onLedgerUpdate);
    }

    private synchronized void onLedgerUpdate(List<LedgerEntry> allLogs) {
        if (isClosed) return;

        this.cachedLogs = allLogs;
        for (LedgerEntry log : allLogs) {
            if (log.seq == 0) knownGenesisLogs.put(log.getAuthorID(), log);
        }

        ledger.processLogBatch(allLogs, ownID);

        List<Transaction> allTransactions = allLogs.stream()
                                                   .map(this::mapLogToTransaction)
                                                   .filter(Objects::nonNull)
                                                   .collect(Collectors.toList());

        boolean isRebuildingSnapshots = isFullHistoryMode ||
                (snapshotLastSeq > 0 && snapshotMintGoc == 0 && snapshotBurnGoc == 0 && snapshotSentGocs.isEmpty() && snapshotReceivedGocs.isEmpty());

        Map<String, Integer> newOutgoingGocs = new HashMap<>();
        Map<String, Integer> allIncomingForBalance = new HashMap<>();
        Map<String, Integer> persistableIncomingGocs = new HashMap<>();

        boolean mintBurnUpdated = false;

        for (Transaction tx : allTransactions) {
            boolean isOutgoing = tx.author().equals(ownID);

            switch (tx) {
                case MintTransaction m when isOutgoing -> {
                    if (m.goc() > snapshotMintGoc) {
                        snapshotMintGoc = (int) m.goc();
                        mintBurnUpdated = true;
                    }
                }
                case BurnTransaction b when isOutgoing -> {
                    if (b.goc() > snapshotBurnGoc) {
                        snapshotBurnGoc = (int) b.goc();
                        mintBurnUpdated = true;
                    }
                }
                case SendTransaction s -> {
                    if (isOutgoing) {
                        snapshotSentGocs.merge(s.target(), (int) s.goc(), Math::max);
                        newOutgoingGocs.put(s.target(), (int) s.goc());
                    } else if (s.target().equals(ownID)) {
                        allIncomingForBalance.merge(s.author(), (int) s.goc(), Math::max);

                        if (isRebuildingSnapshots || sessionVerifiedNewIds.contains(tx.id()) || recoveredTxIds.contains(tx.id())) {
                            persistableIncomingGocs.merge(s.author(), (int) s.goc(), Math::max);
                        }
                    }
                }
                default -> { }
            }
        }

        long totalSent = snapshotSentGocs.values().stream().mapToLong(Integer::longValue).sum();

        int displayBalance = calculateInternalBalance(totalSent, allIncomingForBalance);
        int confirmedBalance = calculateInternalBalance(totalSent, persistableIncomingGocs);

        if (!newOutgoingGocs.isEmpty()) settingsService.importLastSentGocs(ownID, newOutgoingGocs);
        if (mintBurnUpdated) settingsService.updateAccountState(ownID, snapshotMintGoc, snapshotBurnGoc);

        if (!persistableIncomingGocs.isEmpty()) {
            settingsService.importLastReceivedGocs(ownID, persistableIncomingGocs);
            persistableIncomingGocs.forEach((k, v) -> snapshotReceivedGocs.merge(k, v, Math::max));
        }

        settingsService.saveBalance(ownID, displayBalance);

        BalanceResult listResult = ledgerService.calculateBalance(allTransactions, ownID);

        Set<String> unsyncedTxIds = new HashSet<>();
        List<Transaction> trulyUnsyncedTxs = new ArrayList<>();
        long unsyncedSum = 0;

        for (LedgerEntry log : allLogs) {
            if (log.type == Constants.OperationType.SEND.code && ownID.equals(log.getTargetID())) {
                int lastKnown = snapshotReceivedGocs.getOrDefault(log.getAuthorID(), -1);
                if (log.goc > lastKnown) {
                    String txId = CryptoUtils.bytesToHex(log.getHash());
                    unsyncedTxIds.add(txId);
                    Transaction tx = mapLogToTransaction(log);
                    if (tx != null) {
                        trulyUnsyncedTxs.add(tx);
                        long delta = listResult.items().stream()
                                               .filter(item -> item.tx().id().equals(txId))
                                               .findFirst()
                                               .map(TransactionDelta::delta)
                                               .orElse(0L);
                        unsyncedSum += delta;
                    }
                }
            }
        }

        List<TransactionDelta> fullHistory = new ArrayList<>();

        for (TransactionDelta item : listResult.items()) {
            TransactionDelta finalItem = item;
            if (item.tx() instanceof MintTransaction m && m.author().equals(ownID)) {
                if (item.delta() == m.goc() && startupMintGoc > 0) {
                    long corrected = item.delta() - startupMintGoc;
                    if (corrected > 0 && m.goc() > startupMintGoc) finalItem = new TransactionDelta(item.tx(), corrected);
                }
            }
            else if (item.tx() instanceof BurnTransaction b && b.author().equals(ownID)) {
                if (item.delta() == b.goc() && startupBurnGoc > 0) {
                    long corrected = item.delta() - startupBurnGoc;
                    if (corrected > 0 && b.goc() > startupBurnGoc) finalItem = new TransactionDelta(item.tx(), corrected);
                }
            }
            else if (item.tx() instanceof SendTransaction s) {
                if (!s.author().equals(ownID)) {
                    if (item.delta() == s.goc()) {
                        int lastKnown = startupReceivedGocs.getOrDefault(s.author(), 0);
                        if (lastKnown > 0 && s.goc() > lastKnown) {
                            long corrected = item.delta() - lastKnown;
                            finalItem = new TransactionDelta(item.tx(), corrected);
                        }
                    }
                } else {
                    if (item.delta() == s.goc()) {
                        int lastKnown = startupSentGocs.getOrDefault(s.target(), 0);
                        if (lastKnown > 0 && s.goc() > lastKnown) {
                            long corrected = item.delta() - lastKnown;
                            finalItem = new TransactionDelta(item.tx(), corrected);
                        }
                    }
                }
            }

            if (isFullHistoryMode || !unsyncedTxIds.contains(finalItem.tx().id())) fullHistory.add(finalItem);
        }

        List<TransactionDelta> newItems = new ArrayList<>();

        synchronized (processedTxIds) {
            for (TransactionDelta item : fullHistory) {
                String txId = item.tx().id();

                if (sessionVerifiedNewIds.contains(txId)) {
                    if (!processedTxIds.contains(txId)) {
                        processedTxIds.add(txId);
                        newItems.add(item);
                    }
                }
            }
        }

        if (onStateUpdateListener != null) {
            onStateUpdateListener.accept(new WalletState(
                    displayBalance,
                    confirmedBalance,
                    fullHistory,
                    newItems,
                    trulyUnsyncedTxs,
                    knownGenesisLogs.keySet(),
                    unsyncedSum
            ));
        }
    }

    private int calculateInternalBalance(long totalSent, Map<String, Integer> additionalIncoming) {
        Map<String, Integer> combinedMap = new HashMap<>(snapshotReceivedGocs);
        additionalIncoming.forEach((k, v) -> combinedMap.merge(k, v, Math::max));
        long totalRecv = combinedMap.values().stream().mapToLong(Integer::longValue).sum();
        return (int) (snapshotMintGoc - snapshotBurnGoc - totalSent + totalRecv);
    }

    public synchronized void syncIncomingTransactions(List<Transaction> txs) {
        if (isClosed) return;
        if (txs.isEmpty()) return;

        List<LedgerEntry> entriesToSync = new ArrayList<>();
        for (Transaction tx : txs) {
            cachedLogs.stream()
                      .filter(l -> CryptoUtils.bytesToHex(l.getHash()).equals(tx.id()))
                      .findFirst()
                      .ifPresent(entriesToSync::add);
        }
        if (entriesToSync.isEmpty()) return;

        for (LedgerEntry log : entriesToSync) {
            internalSyncCard(Collections.singletonList(log));
            onLedgerUpdate(this.cachedLogs);

            try { Thread.sleep(20); } catch(InterruptedException ignored) {}
        }
    }

    private void internalSyncCard(List<LedgerEntry> entriesToSync) {
        for (LedgerEntry log : entriesToSync) {
            try {
                applyLogToCard(log);
                sessionVerifiedNewIds.add(CryptoUtils.bytesToHex(log.getHash()));
            } catch (Exception e) {
                if (isCardAlreadySyncedError(e)) {
                    recoveredTxIds.add(CryptoUtils.bytesToHex(log.getHash()));
                } else {
                    System.err.println("Manual-Sync failed: " + e.getMessage());
                }
            }
        }
    }

    private boolean isCardAlreadySyncedError(Exception e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("6985") || msg.contains("Conditions not satisfied"));
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
        try {
            ensurePeerRegistered(receiverID);
        } catch (Exception ignored) {}
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

        if (ledger != null) ledger.processLogBatch(Collections.singletonList(entry), ownID);
    }

    private Transaction executeTx(byte[] payload, CardOperation op) throws Exception {
        byte[] response = op.execute(payload);
        LedgerEntry entry = LedgerEntry.fromCardResponse(response);
        String txId = CryptoUtils.bytesToHex(entry.getHash());

        sessionVerifiedNewIds.add(txId);
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
    public synchronized void close() {
        isClosed = true;
        if (settingsService != null) settingsService.persist();
        if (ledgerService != null) ledgerService.stopObserving();
    }

    @FunctionalInterface
    interface CardOperation {
        byte[] execute(byte[] input) throws Exception;
    }
}