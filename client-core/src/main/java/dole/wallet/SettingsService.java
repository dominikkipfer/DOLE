package dole.wallet;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dole.Constants;
import dole.ledger.Ledger;
import dole.ledger.Ledger.AccountState;
import dole.ledger.Ledger.LedgerState;

public class SettingsService {
    private final File storageFile;
    private final Gson gson;
    public List<StoredAccount> accounts = new CopyOnWriteArrayList<>();

    private final Map<String, List<String>> pendingActions = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Integer>> lastReceivedTx = new ConcurrentHashMap<>();
    private final Map<String, Integer> savedBalances = new ConcurrentHashMap<>();

    private LedgerState initialLedgerState = new LedgerState();
    private Ledger activeLedger;

    public SettingsService(String rootPath) {
        File dir = new File(rootPath);
        if (!dir.exists()) dir.mkdirs();
        this.storageFile = new File(dir, Constants.FILE_WALLET_DATA);
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        loadData();
    }

    public void attachLedger(Ledger ledger) {
        this.activeLedger = ledger;
        if (this.initialLedgerState != null) {
            ledger.initialize(this.initialLedgerState);
        }
    }

    public void saveBalance(String userId, int balance) {
        savedBalances.put(userId, balance);
        persist();
    }

    public int getLastBalance(String userId) {
        return savedBalances.getOrDefault(userId, 0);
    }

    public List<String> getKnownPeers(String myId) {
        Set<String> peers = new HashSet<>();
        Map<String, Integer> senders = lastReceivedTx.get(myId);
        if (senders != null) peers.addAll(senders.keySet());

        for (StoredAccount acc : accounts) {
            if (!acc.id().equals(myId)) peers.add(acc.id());
        }

        return new ArrayList<>(peers);
    }

    public void saveAccount(String id, String name, String pinHash) {
        accounts.removeIf(acc -> acc.id().equals(id));
        accounts.add(new StoredAccount(id, name, pinHash));
        persist();
    }

    public void removeAccount(String id) {
        accounts.removeIf(acc -> acc.id().equals(id));
        pendingActions.remove(id);
        lastReceivedTx.remove(id);
        savedBalances.remove(id);
        persist();
    }

    public void savePendingActions(String userId, List<String> actions) {
        if (actions == null || actions.isEmpty()) pendingActions.remove(userId);
        else pendingActions.put(userId, actions);
        persist();
    }

    public List<String> loadPendingActions(String userId) {
        return pendingActions.getOrDefault(userId, new ArrayList<>());
    }

    public Map<String, Integer> getLastReceivedGocs(String userId) {
        return new HashMap<>(lastReceivedTx.getOrDefault(userId, new HashMap<>()));
    }

    public void updateLastReceivedGoc(String myId, String senderId, int newGoc) {
        Map<String, Integer> senderMap = lastReceivedTx.computeIfAbsent(myId, k -> new HashMap<>());
        Integer current = senderMap.getOrDefault(senderId, -1);
        if (newGoc > current) {
            senderMap.put(senderId, newGoc);
            persist();
        }
    }

    public synchronized void persist() {
        Map<String, CombinedUserData> exportMap = new HashMap<>();
        Set<String> myIds = new HashSet<>();
        for (StoredAccount acc : accounts) {
            CombinedUserData data = exportMap.computeIfAbsent(acc.id(), k -> new CombinedUserData());
            data.name = acc.name();
            data.pinHash = acc.pinHash();
            myIds.add(acc.id());
        }

        pendingActions.forEach((id, actions) -> {
            if (!actions.isEmpty()) exportMap.computeIfAbsent(id, k -> new CombinedUserData()).pendingActions = new ArrayList<>(actions);
        });

        lastReceivedTx.forEach((id, map) -> {
            if (!map.isEmpty()) exportMap.computeIfAbsent(id, k -> new CombinedUserData()).lastReceivedTx = new HashMap<>(map);
        });

        savedBalances.forEach((id, bal) -> {
            exportMap.computeIfAbsent(id, k -> new CombinedUserData()).savedBalance = bal;
        });

        LedgerState ls = (activeLedger != null) ? activeLedger.exportState() : initialLedgerState;

        if (ls != null) {
            ls.keys.forEach((id, key) -> exportMap.computeIfAbsent(id, k -> new CombinedUserData()).publicKey = key);
            ls.hashes.forEach((id, hash) -> {
                if (myIds.contains(id) || exportMap.containsKey(id)) {
                    CombinedUserData data = exportMap.computeIfAbsent(id, k -> new CombinedUserData());
                    data.lastHash = hash;
                    AccountState as = ls.states.get(id);
                    if (as != null) {
                        data.lastSeq = as.lastSeq;
                        data.totalMinted = as.totalMinted;
                        data.totalBurned = as.totalBurned;
                    }
                }
            });
        }

        try (Writer writer = new FileWriter(storageFile)) {
            gson.toJson(exportMap, writer);
            writer.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadData() {
        if (!storageFile.exists()) return;

        try (Reader reader = new FileReader(storageFile)) {
            java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<Map<String, CombinedUserData>>(){}.getType();
            Map<String, CombinedUserData> dataMap = gson.fromJson(reader, type);

            if (dataMap == null) return;
            initialLedgerState = new LedgerState();

            dataMap.forEach((id, data) -> {
                if (data.name != null) accounts.add(new StoredAccount(id, data.name, data.pinHash != null ? data.pinHash : ""));
                if (data.pendingActions != null) pendingActions.put(id, data.pendingActions);
                if (data.lastReceivedTx != null) lastReceivedTx.put(id, new ConcurrentHashMap<>(data.lastReceivedTx));
                if (data.savedBalance != null) savedBalances.put(id, data.savedBalance);

                if (data.lastHash != null || data.publicKey != null) {
                    AccountState as = new AccountState();
                    as.lastSeq = data.lastSeq;
                    as.totalMinted = data.totalMinted;
                    as.totalBurned = data.totalBurned;
                    initialLedgerState.states.put(id, as);
                    if (data.lastHash != null) initialLedgerState.hashes.put(id, data.lastHash);
                    if (data.publicKey != null) initialLedgerState.keys.put(id, data.publicKey);
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static class CombinedUserData {
        String name;
        String pinHash;
        List<String> pendingActions;
        Map<String, Integer> lastReceivedTx;
        Integer savedBalance;
        String lastHash;
        String publicKey;
        int lastSeq = -1;
        int totalMinted = 0;
        int totalBurned = 0;
    }
}