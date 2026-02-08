package dole.ledger;

import android.content.Context;
import android.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import dole.Constants;
import dole.crypto.CryptoUtils;
import kotlin.Unit;
import live.ditto.Ditto;
import live.ditto.DittoError;
import live.ditto.DittoIdentity;
import live.ditto.DittoQueryResultItem;
import live.ditto.android.DefaultAndroidDittoDependencies;
import org.json.JSONObject;

public class AndroidLedgerService implements LedgerService {

    private static final String TAG = "AndroidLedger";
    private static AndroidLedgerService instance;
    private final Ditto ditto;
    private AutoCloseable activeSubscription;
    private AutoCloseable activeObserver;

    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean isRunning = new AtomicBoolean(true);

    private String currentObservedId;
    private int currentLastSeq = 0;
    private Map<String, Integer> currentLastReceivedGocs = new HashMap<>();

    private Consumer<List<LedgerEntry>> currentLogConsumer;
    private boolean isFullHistoryMode = false;

    private AndroidLedgerService(Context context, String appId, String token) {
        DefaultAndroidDittoDependencies deps = new DefaultAndroidDittoDependencies(context);

        DittoIdentity identity = new DittoIdentity.OnlinePlayground(
                deps,
                appId,
                token,
                false,
                Constants.DITTO_AUTH_URL
        );

        try {
            this.ditto = new Ditto(deps, identity);

            try {
                this.ditto.updateTransportConfig(config -> {
                    String wsUrl = Constants.DITTO_WEBSOCKET_URL;
                    config.getConnect().getWebsocketUrls().add(wsUrl);
                    return Unit.INSTANCE;
                });
                Log.i(TAG, "Transport configured for Big Peer: " + Constants.DITTO_WEBSOCKET_URL);
            } catch (Exception e) {
                Log.e(TAG, "Failed to update transport config", e);
            }

            try {
                this.ditto.disableSyncWithV3();
            } catch (Exception ignored) {}

            startSyncLoop();

        } catch (DittoError e) {
            Log.e(TAG, "FATAL: Failed to start Ditto Service locally", e);
            throw new RuntimeException("Ditto Local Init Failed", e);
        }
    }

    public static synchronized AndroidLedgerService getInstance(Context context, String appId, String token) {
        if (instance == null) instance = new AndroidLedgerService(context.getApplicationContext(), appId, token);
        return instance;
    }

    private void startSyncLoop() {
        syncExecutor.execute(() -> {
            boolean isConnected = false;
            Log.i(TAG, "Starting background connection loop");

            while (!isConnected && isRunning.get()) {
                try {
                    this.ditto.startSync();
                    isConnected = true;
                    Log.i(TAG, "ONLINE: Ditto Sync Started successfully.");
                } catch (DittoError e) {
                    Log.w(TAG, "OFFLINE: Retrying in 5 seconds" + e.getMessage());
                    try { Thread.sleep(5000); } catch (InterruptedException ie) { break; }
                } catch (Exception e) {
                    Log.e(TAG, "Unexpected error in sync loop", e);
                    break;
                }
            }
        });
    }

    @Override
    public void saveEntry(LedgerEntry entry) {
        String docId = entry.getAuthorID() + "_" + entry.seq;
        String query = "INSERT INTO " + Constants.COLLECTION + " DOCUMENTS (:newLog) ON ID CONFLICT DO UPDATE";

        Map<String, Object> doc = new HashMap<>();
        doc.put("_id", docId);
        doc.put("type", Constants.OperationType.fromCode(entry.type).name());
        doc.put("seq", entry.seq);
        doc.put("author", entry.getAuthorID());
        doc.put("target", entry.getTargetID());
        doc.put("goc", entry.goc);
        doc.put("prevHash", CryptoUtils.bytesToHex(entry.getPrevHash()));
        doc.put("signature", CryptoUtils.bytesToHex(entry.getSignature()));

        if (entry.getAttachmentPublicKey() != null) {
            doc.put("pubKey", CryptoUtils.bytesToHex(entry.getAttachmentPublicKey()));
        }
        if (entry.getAttachmentCertificate() != null) {
            doc.put("cert", CryptoUtils.bytesToHex(entry.getAttachmentCertificate()));
        }

        Map<String, Object> args = new HashMap<>();
        args.put("newLog", doc);

        try {
            this.ditto.store.execute(query, args);
        } catch (DittoError e) {
            Log.e(TAG, "CRITICAL: Failed to save log " + docId, e);
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
        Log.i(TAG, "Switching Sync Mode. Full History: " + loadFullHistory);
        restartObserver();
    }

    private void restartObserver() {
        closeObserver();
        if (currentObservedId == null || currentLogConsumer == null) return;

        String whereString = buildWhereClause();
        String syncQuery = String.format("SELECT * FROM %s WHERE %s", Constants.COLLECTION, whereString);
        String observerQuery = String.format("SELECT * FROM %s WHERE %s ORDER BY seq ASC", Constants.COLLECTION, whereString);

        Map<String, Object> args = new HashMap<>();
        args.put("myId", currentObservedId);
        args.put("lastSeq", currentLastSeq);

        try {
            this.activeSubscription = this.ditto.sync.registerSubscription(syncQuery, args);
            this.activeObserver = this.ditto.store.registerObserver(observerQuery, args, result -> {
                List<LedgerEntry> entries = result.getItems().stream()
                                                  .map(this::mapToLedgerEntry)
                                                  .filter(Objects::nonNull)
                                                  .filter(this::isLogRelevant)
                                                  .collect(Collectors.toList());

                if (!entries.isEmpty()) {
                    Log.d(TAG, "Received " + entries.size() + " entries.");
                    currentLogConsumer.accept(entries);
                }
                return Unit.INSTANCE;
            });
        } catch (DittoError e) {
            Log.e(TAG, "Observer error", e);
        }
    }

    private String buildWhereClause() {
        if (isFullHistoryMode) {
            return "author = :myId OR target = :myId OR seq = 0";
        } else {
            return "(seq = 0) OR (author = :myId AND seq > :lastSeq) OR (target = :myId)";
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
            Map<String, Object> val = item.getValue();
            JSONObject json = new JSONObject();

            json.put(Constants.KEY_SEQ, safeInt(val.get("seq")));
            json.put(Constants.KEY_AUTHOR, val.get("author"));
            json.put(Constants.KEY_TYPE, val.get("type"));
            json.put(Constants.KEY_GOC, safeInt(val.get("goc")));
            json.put(Constants.KEY_PREV_HASH, val.get("prevHash"));
            json.put(Constants.KEY_SIGNATURE, val.get("signature"));

            Object target = val.get("target");
            json.put(Constants.KEY_TARGET, target != null ? target.toString() : val.get("author"));

            if (val.containsKey("pubKey")) json.put(Constants.KEY_PUBKEY, val.get("pubKey"));
            if (val.containsKey("cert")) json.put(Constants.KEY_CERT, val.get("cert"));

            return LedgerEntry.fromJSON(json.toString());
        } catch (Exception e) {
            Log.e(TAG, "Mapping error", e);
            return null;
        }
    }

    private int safeInt(Object obj) {
        if (obj instanceof Number) return ((Number) obj).intValue();
        if (obj instanceof String) try {
            return Integer.parseInt((String) obj);
        } catch(Exception ignored){}
        return 0;
    }

    private void closeObserver() {
        try {
            if (activeSubscription != null) activeSubscription.close();
            if (activeObserver != null) activeObserver.close();
        } catch (Exception ignored) {}
    }

    @Override
    public synchronized void close() {
        isRunning.set(false);
        closeObserver();
        if (this.ditto != null) {
            try {
                this.ditto.stopSync();
                this.ditto.close();
            } catch(Exception ignored) {}
        }
    }

    @Override
    public void stopObserving() {
        closeObserver();
        this.currentObservedId = null;
        this.currentLogConsumer = null;
        this.currentLastReceivedGocs.clear();
        this.isFullHistoryMode = false;
    }
}