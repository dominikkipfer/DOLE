package dole.balance;

import java.util.List;

public record BalanceResult(int totalBalance, List<TransactionDelta> items) {}