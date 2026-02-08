package dole.card;

public interface SmartCard {
    void connect() throws Exception;
    void disconnect();

    default boolean isConnected() { return false; }
    boolean isPinSet() throws Exception;
    boolean isGenesisDone() throws Exception;
    boolean verifyPin(byte[] pin) throws Exception;
    boolean changePin(byte[] newPin) throws Exception;
    byte[] getPublicKey() throws Exception;
    byte[] getCertificate() throws Exception;
    byte[] processGenesis() throws Exception;
    byte[] processMint(byte[] payload) throws Exception;
    byte[] processBurn(byte[] payload) throws Exception;
    byte[] processSend(byte[] payload) throws Exception;
    void processReceive(byte[] payload) throws Exception;
    void addPeer(byte[] payload) throws Exception;
}