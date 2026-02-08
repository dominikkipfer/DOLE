package dole.transaction;

// MINT
public record MintTransaction(
        String id,
        int seq,
        String author,
        long goc
) implements Transaction {}
