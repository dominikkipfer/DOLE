package dole.ledger;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.ditto.java.Ditto;
import com.ditto.java.DittoAuthenticationProvider;
import com.ditto.java.DittoConfig;
import com.ditto.java.DittoFactory;
import com.ditto.java.DittoQueryResultItem;
import com.ditto.java.serialization.DittoCborSerializable;
import dole.Constants;
import dole.crypto.CryptoUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PCLedgerService implements LedgerService {

    private static final Logger logger = LoggerFactory.getLogger(PCLedgerService.class);
    private final Ditto ditto;
    private AutoCloseable activeSubscription;
    private AutoCloseable activeObserver;
    private volatile boolean isRunning = true;

    private String currentObservedId;
    private int currentLastSeq = 0;
    private Map<String, Integer> currentLastReceivedGocs = new HashMap<>();

    private Consumer<List<LedgerEntry>> currentLogConsumer;
    private boolean isFullHistoryMode = false;

    public PCLedgerService(String storagePath, String appId, String token) {
        File workingDir = new File(storagePath, "ditto_data");
        if (!workingDir.exists()) workingDir.mkdirs();

        DittoConfig config = new DittoConfig.Builder(appId)
                .serverConnect(Constants.DITTO_AUTH_URL)
                .persistenceDirectory(workingDir.getAbsolutePath())
                .build();

        try {
            this.ditto = DittoFactory.create(config);
            this.ditto.setDeviceName("PC Wallet");

            if (this.ditto.getAuth() == null) throw new RuntimeException("Ditto Auth is null");

            this.ditto.getAuth().setExpirationHandler((expiringDitto, duration) ->
                    Objects.requireNonNull(expiringDitto.getAuth())
                           .login(token, DittoAuthenticationProvider.development()).thenApply(result -> null)
            );

            startSyncLoop(token);

        } catch (Exception e) {
            logger.error("FATAL: Failed to start Ditto Service locally", e);
            throw new RuntimeException("Ditto Local Init Failed", e);
        }
    }

    private void startSyncLoop(String token) {
        Thread connectionThread = new Thread(() -> {
            boolean isConnected = false;
            logger.info("Starting background connection loop");
            while (!isConnected && isRunning) {
                try {
                    Objects.requireNonNull(this.ditto.getAuth())
                           .login(token, DittoAuthenticationProvider.development())
                           .toCompletableFuture()
                           .get(10, TimeUnit.SECONDS);

                    this.ditto.startSync();
                    isConnected = true;
                    logger.info("ONLINE: Ditto Sync Started successfully.");
                } catch (Exception e) {
                    logger.warn("OFFLINE: Retrying in 5s Error: {}", e.getMessage());
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        });
        connectionThread.setDaemon(true);
        connectionThread.setName("DittoConnectionManager");
        connectionThread.start();
    }

    @Override
    public void saveEntry(LedgerEntry entry) {
        String docId = entry.getAuthorID() + "_" + entry.seq;
        String query = "INSERT INTO %s DOCUMENTS (:newLog) ON ID CONFLICT DO UPDATE".formatted(Constants.COLLECTION);
        String dbTarget = entry.getTargetID();

        var builder = DittoCborSerializable.Dictionary.buildDictionary()
                                                      .put("_id", docId)
                                                      .put("type", Constants.OperationType.fromCode(entry.type).name())
                                                      .put("seq", entry.seq)
                                                      .put("author", entry.getAuthorID())
                                                      .put("target", dbTarget)
                                                      .put("goc", entry.goc)
                                                      .put("prevHash", CryptoUtils.bytesToHex(entry.getPrevHash()))
                                                      .put("signature", CryptoUtils.bytesToHex(entry.getSignature()));

        if (entry.getAttachmentPublicKey() != null) {
            builder.put("pubKey", CryptoUtils.bytesToHex(entry.getAttachmentPublicKey()));
        }
        if (entry.getAttachmentCertificate() != null) {
            builder.put("cert", CryptoUtils.bytesToHex(entry.getAttachmentCertificate()));
        }

        var args = DittoCborSerializable.Dictionary.buildDictionary().put("newLog", builder.build()).build();

        try {
            this.ditto.getStore().execute(query, args).toCompletableFuture().get();
        } catch (Exception e) {
            logger.error("CRITICAL: Failed to save log {}", docId, e);
            throw new RuntimeException("Database Write Failed: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void observeRelevantLogs(String myId, int lastKnownSeq, Map<String, Integer> lastReceivedGocs, Consumer<List<LedgerEntry>> onLogsUpdated) {
        this.currentObservedId = myId;
        this.currentLastSeq = lastKnownSeq;
        this.currentLastReceivedGocs = (lastReceivedGocs != null) ? new HashMap<>(lastReceivedGocs) : new HashMap<>();
        this.currentLogConsumer = onLogsUpdated;
        restartObserver();
    }

    @Override
    public synchronized void setHistorySyncMode(boolean loadFullHistory) {
        if (this.isFullHistoryMode == loadFullHistory) return;
        this.isFullHistoryMode = loadFullHistory;
        logger.info("Switching Sync Mode. Full History: {}", loadFullHistory);
        restartObserver();
    }

    private void restartObserver() {
        closeObserver();
        if (currentObservedId == null || currentLogConsumer == null) return;

        String whereString = buildWhereClause();
        String syncQuery = "SELECT * FROM %s WHERE %s".formatted(Constants.COLLECTION, whereString);
        String observerQuery = "SELECT * FROM %s WHERE %s ORDER BY seq ASC".formatted(Constants.COLLECTION, whereString);

        logger.debug("Sync Query: {}", syncQuery);

        var args = DittoCborSerializable.Dictionary.buildDictionary()
                                                   .put("myId", currentObservedId).put("lastSeq", currentLastSeq).build();

        try {
            try {
                this.activeSubscription = this.ditto.getSync().registerSubscription(syncQuery, args);
            } catch (Exception e) {
                logger.warn("Subscription pending/failed: {}", e.getMessage());
            }

            this.activeObserver = this.ditto.getStore().registerObserver(observerQuery, args, result -> {
                List<LedgerEntry> entries = result.getItems().stream()
                                                  .map(this::mapToLedgerEntry)
                                                  .filter(Objects::nonNull)
                                                  .filter(this::isLogRelevant)
                                                  .collect(Collectors.toList());

                if (!entries.isEmpty()) currentLogConsumer.accept(entries);
            });
        } catch (Exception e) {
            logger.error("Failed to register observer", e);
        }
    }

    private String buildWhereClause() {
        if (isFullHistoryMode) {
            return "author = :myId OR target = :myId OR seq = 0";
        } else {
            return "(seq = 0) " + "OR (author = :myId AND seq > :lastSeq) " + "OR (target = :myId)";
        }
    }

    private boolean isLogRelevant(LedgerEntry entry) {
        if (isFullHistoryMode) return true;
        if (entry.seq == 0) return true;

        if (entry.getAuthorID().equals(currentObservedId)) return entry.seq > currentLastSeq;
        if (entry.getTargetID().equals(currentObservedId)) {
            Integer storedGoc = currentLastReceivedGocs.get(entry.getAuthorID());
            int lastKnownGoc = (storedGoc != null) ? storedGoc : -1;
            return entry.goc > lastKnownGoc;
        }

        return false;
    }

    private LedgerEntry mapToLedgerEntry(DittoQueryResultItem item) {
        try {
            var val = item.getValue();
            JSONObject json = new JSONObject();

            json.put(Constants.KEY_SEQ, val.get("seq").asInt());
            json.put(Constants.KEY_AUTHOR, val.get("author").asString());
            json.put(Constants.KEY_TYPE, val.get("type").asString());
            json.put(Constants.KEY_GOC, val.get("goc").asInt());
            json.put(Constants.KEY_PREV_HASH, val.get("prevHash").asString());
            json.put(Constants.KEY_SIGNATURE, val.get("signature").asString());

            try {
                String target = val.get("target").asString();
                json.put(Constants.KEY_TARGET, target);
            } catch (Exception e) {
                json.put(Constants.KEY_TARGET, val.get("author").asString());
            }

            try {
                String pk = val.get("pubKey").asString();
                json.put(Constants.KEY_PUBKEY, pk);
            } catch (Exception ignored) {}

            try {
                String cert = val.get("cert").asString();
                json.put(Constants.KEY_CERT, cert);
            } catch (Exception ignored) {}

            return LedgerEntry.fromJSON(json.toString());
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public synchronized void stopObserving() {
        logger.info("Stopping active observation.");
        closeObserver();
        this.currentObservedId = null;
        this.currentLogConsumer = null;
        this.currentLastReceivedGocs.clear();
        this.isFullHistoryMode = false;
    }

    private void closeObserver() {
        try {
            if (activeSubscription != null) activeSubscription.close();
            if (activeObserver != null) activeObserver.close();
            activeSubscription = null;
            activeObserver = null;
        } catch (Exception ignored) {}
    }

    @Override
    public synchronized void close() {
        isRunning = false;
        stopObserving();
        if (this.ditto != null) {
            try {
                this.ditto.stopSync();
                this.ditto.close();
            } catch (Exception e) {
                logger.error("Error closing Ditto", e);
            }
        }
    }
}