package dole.ledger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import android.content.Context;
import android.util.Log;
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

    private AndroidLedgerService(Context context, String appId, String token) {
        DefaultAndroidDittoDependencies deps = new DefaultAndroidDittoDependencies(context);
        DittoIdentity identity = new DittoIdentity.OnlinePlayground(deps, appId, token, false);

        try {
            this.ditto = new Ditto(deps, identity);
            try { this.ditto.disableSyncWithV3(); } catch(Exception ignored) {}

            startSyncLoop();

        } catch (DittoError e) {
            Log.e(TAG, "FATAL: Failed to start Ditto Service locally", e);
            throw new RuntimeException("Ditto Local Init Failed", e);
        }
    }

    public static synchronized AndroidLedgerService getInstance(Context context, String appId, String token) {
        if (instance == null) {
            instance = new AndroidLedgerService(context.getApplicationContext(), appId, token);
        }
        return instance;
    }

    private void startSyncLoop() {
        syncExecutor.execute(() -> {
            boolean isConnected = false;
            Log.i(TAG, "Starting background connection loop...");

            while (!isConnected && isRunning.get()) {
                try {
                    this.ditto.startSync();
                    isConnected = true;
                    Log.i(TAG, "ONLINE: Ditto Sync Started successfully.");
                } catch (DittoError e) {
                    Log.w(TAG, "OFFLINE: Could not connect to Cloud. Retrying in 5 seconds... Error: " + e.getMessage());
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        break;
                    }
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
        String query = String.format("INSERT INTO %s DOCUMENTS (:newLog) ON ID CONFLICT DO UPDATE", Constants.COLLECTION);

        String dbTarget = entry.getTargetID();
        if (dbTarget == null || dbTarget.isEmpty() || dbTarget.matches("0+")) {
            dbTarget = entry.getAuthorID();
        }

        Map<String, Object> doc = new HashMap<>();
        doc.put("_id", docId);
        doc.put("type", Constants.OperationType.fromCode(entry.type).name());
        doc.put("seq", entry.seq);
        doc.put("author", entry.getAuthorID());
        doc.put("target", dbTarget);
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
    public void observeRelevantLogs(String myId, Consumer<List<LedgerEntry>> onLogsUpdated) {
        closeObserver();

        String syncQuery = String.format("SELECT * FROM %s WHERE author = :myId OR target = :myId OR seq = 0", Constants.COLLECTION);
        String observerQuery = String.format("SELECT * FROM %s WHERE author = :myId OR target = :myId OR seq = 0 ORDER BY seq ASC", Constants.COLLECTION);

        Map<String, Object> args = new HashMap<>();
        args.put("myId", myId);

        try {
            this.activeSubscription = this.ditto.sync.registerSubscription(syncQuery, args);

            this.activeObserver = this.ditto.store.registerObserver(observerQuery, args, result -> {
                List<LedgerEntry> entries = result.getItems().stream()
                                                  .map(this::mapToLedgerEntry)
                                                  .filter(Objects::nonNull)
                                                  .collect(Collectors.toList());

                onLogsUpdated.accept(entries);
                return Unit.INSTANCE;
            });

        } catch (DittoError e) {
            Log.e(TAG, "Failed to register observer/subscription", e);
            throw new RuntimeException("Sync Error: " + e.getMessage(), e);
        } catch (Exception e) {
            Log.e(TAG, "Unknown error in observer registration", e);
            throw new RuntimeException("Observer Error: " + e.getMessage(), e);
        }
    }

    private LedgerEntry mapToLedgerEntry(DittoQueryResultItem item) {
        try {
            Map<String, Object> val = item.getValue();
            JSONObject json = new JSONObject();

            json.put(Constants.KEY_SEQ, val.get("seq"));
            json.put(Constants.KEY_AUTHOR, val.get("author"));
            json.put(Constants.KEY_TYPE, val.get("type"));
            json.put(Constants.KEY_GOC, val.get("goc"));
            json.put(Constants.KEY_PREV_HASH, val.get("prevHash"));
            json.put(Constants.KEY_SIGNATURE, val.get("signature"));

            try {
                Object target = val.get("target");
                json.put(Constants.KEY_TARGET, target != null ? target : val.get("author"));
            } catch (Exception e) {
                json.put(Constants.KEY_TARGET, val.get("author"));
            }

            if (val.containsKey("pubKey")) {
                json.put(Constants.KEY_PUBKEY, val.get("pubKey"));
            }
            if (val.containsKey("cert")) {
                json.put(Constants.KEY_CERT, val.get("cert"));
            }

            return LedgerEntry.fromJSON(json.toString());
        } catch (Exception e) {
            return null;
        }
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
            } catch(Exception e) {
                Log.e(TAG, "Error closing Ditto", e);
            }
        }
    }
}