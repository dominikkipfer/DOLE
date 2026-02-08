package dole.ledger;

import java.io.Closeable;
import java.util.List;
import java.util.function.Consumer;

import dole.balance.BalanceCalculator;
import dole.balance.BalanceResult;
import dole.transaction.Transaction;

public interface LedgerService extends Closeable {
    void saveEntry(LedgerEntry entry);

    void observeRelevantLogs(String myId, Consumer<List<LedgerEntry>> onLogsUpdated);

    default BalanceResult calculateBalance(List<Transaction> transactions, String currentId) {
        return BalanceCalculator.calculate(transactions, currentId);
    }
}