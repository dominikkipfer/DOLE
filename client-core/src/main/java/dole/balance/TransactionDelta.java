package dole.balance;

import dole.transaction.Transaction;

public record TransactionDelta(Transaction tx, long delta) {}