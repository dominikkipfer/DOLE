package dole.ledger;

import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import dole.balance.BalanceCalculator;
import dole.balance.BalanceResult;
import dole.transaction.Transaction;

public interface LedgerService extends Closeable {
    void saveEntry(LedgerEntry entry);

    void observeRelevantLogs(String myId, int lastKnownSeq, Map<String, Integer> lastReceivedGocs, Consumer<List<LedgerEntry>> onLogsUpdated);

    void setHistorySyncMode(boolean loadFullHistory);

    void stopObserving();

    default BalanceResult calculateBalance(List<Transaction> transactions, String currentId) {
        return BalanceCalculator.calculate(transactions, currentId);
    }
}