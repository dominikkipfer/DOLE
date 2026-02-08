package dole.transaction;

// GENESIS
public record GenesisTransaction(
        String id,
        int seq,
        String author,
        byte[] attachmentCertificate
) implements Transaction {}
