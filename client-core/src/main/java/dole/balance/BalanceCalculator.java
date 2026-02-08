package dole.balance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dole.transaction.BurnTransaction;
import dole.transaction.GenesisTransaction;
import dole.transaction.MintTransaction;
import dole.transaction.SendTransaction;
import dole.transaction.Transaction;

public class BalanceCalculator {
    public static BalanceResult calculate(List<Transaction> transactions, String currentId) {
        if (currentId == null || transactions == null) return new BalanceResult(0, new ArrayList<>());

        List<Transaction> sorted = new ArrayList<>(transactions);
        sorted.sort(Comparator.comparingLong(Transaction::seq));

        List<TransactionDelta> displayList = new ArrayList<>();

        long myMintGoc = 0;
        long myBurnGoc = 0;

        Map<String, Long> mySendGocs = new HashMap<>();
        Map<String, Long> receivedGocs = new HashMap<>();

        int totalBalance = 0;

        for (Transaction tx : sorted) {
            boolean isAuthor = currentId.equals(tx.author());
            boolean isTarget = (tx instanceof SendTransaction s) && currentId.equals(s.target());

            if (!isAuthor && !isTarget) continue;

            long delta = 0;

            switch (tx) {
                case MintTransaction m -> {
                    if (m.goc() > myMintGoc) {
                        delta = m.goc() - myMintGoc;
                        myMintGoc = m.goc();
                        totalBalance += (int) delta;
                    }
                }
                case BurnTransaction b -> {
                    if (b.goc() > myBurnGoc) {
                        delta = b.goc() - myBurnGoc;
                        myBurnGoc = b.goc();
                        totalBalance -= (int) delta;
                    }
                }
                case SendTransaction s -> {
                    if (isAuthor) {
                        String target = s.target();
                        long lastGoc = mySendGocs.getOrDefault(target, 0L);

                        if (s.goc() > lastGoc) {
                            delta = s.goc() - lastGoc;
                            mySendGocs.put(target, s.goc());
                            totalBalance -= (int) delta;
                        }
                    } else {
                        String sender = s.author();
                        long lastGoc = receivedGocs.getOrDefault(sender, 0L);

                        if (s.goc() > lastGoc) {
                            delta = s.goc() - lastGoc;
                            receivedGocs.put(sender, s.goc());
                            totalBalance += (int) delta;
                        }
                    }
                }
                default -> {
                }
            }
            if (delta > 0 || (tx instanceof GenesisTransaction && isAuthor)) {
                displayList.add(new TransactionDelta(tx, delta));
            }
        }
        Collections.reverse(displayList);
        return new BalanceResult(totalBalance, displayList);
    }
}