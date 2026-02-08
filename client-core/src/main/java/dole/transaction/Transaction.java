package dole.transaction;

public sealed interface Transaction permits GenesisTransaction, MintTransaction, BurnTransaction, SendTransaction {
    String id();
    int seq();
    String author();
}