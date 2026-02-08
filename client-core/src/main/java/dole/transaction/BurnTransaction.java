package dole.transaction;

// BURN
public record BurnTransaction(
        String id,
        int seq,
        String author,
        long goc
) implements Transaction {}
