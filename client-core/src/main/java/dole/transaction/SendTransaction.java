package dole.transaction;

// SEND
public record SendTransaction(
        String id,
        int seq,
        String author,
        String target,
        long goc
) implements Transaction {}
