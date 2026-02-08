package dole.card;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import javax.smartcardio.CommandAPDU;

import dole.Constants;

public class PCSmartCard implements SmartCard {

    private Process serviceProcess;
    private BufferedWriter writer;
    private BufferedReader reader;
    private boolean isConnectedInternal = false;

    @Override
    public boolean isConnected() {
        return isConnectedInternal && serviceProcess != null && serviceProcess.isAlive();
    }

    private synchronized void startService() throws Exception {
        if (serviceProcess != null && serviceProcess.isAlive()) return;
        killService();

        try {
            String javaHome = System.getProperty("java.home");
            String os = System.getProperty("os.name").toLowerCase();
            String javaBin = javaHome + (os.contains("win") ? "/bin/java.exe" : "/bin/java");
            String classpath = System.getProperty("java.class.path");

            ProcessBuilder builder = new ProcessBuilder(javaBin, "-cp", classpath, "dole.card.SmartCardService");

            serviceProcess = builder.start();
            writer = new BufferedWriter(new OutputStreamWriter(serviceProcess.getOutputStream()));
            reader = new BufferedReader(new InputStreamReader(serviceProcess.getInputStream()));

            String line = reader.readLine();
            if (!"READY".equals(line)) {
                throw new Exception(line);
            }
        } catch (Exception e) {
            killService();
            throw new Exception(e.getMessage());
        }
    }

    private synchronized void killService() {
        if (serviceProcess == null) return;

        try {
            if (writer != null) {
                writer.write("EXIT");
                writer.newLine();
                writer.flush();
            }
        } catch (Exception ignored) {}

        serviceProcess.destroy();
        try {
            if (!serviceProcess.waitFor(1, TimeUnit.SECONDS)) serviceProcess.destroyForcibly();
        } catch (InterruptedException e) {
            serviceProcess.destroyForcibly();
            Thread.currentThread().interrupt();
        }

        serviceProcess = null;
        writer = null;
        reader = null;
        isConnectedInternal = false;
    }

    private synchronized String sendCommand(String cmd) throws Exception {
        if (serviceProcess == null || !serviceProcess.isAlive()) startService();

        try {
            writer.write(cmd);
            writer.newLine();
            writer.flush();

            String response = reader.readLine();
            if (response == null) throw new Exception("EOF");

            if (response.startsWith("ERROR:")) {
                String msg = response.substring(6);

                boolean isHarmless = msg.contains(Constants.ERR_CODE_NO_READER) ||
                        msg.contains(Constants.ERR_CARD_NOT_FOUND) ||
                        msg.contains("0x8010002f") ||
                        msg.contains("0x80100069") ||
                        msg.contains("0x1f") ||
                        msg.contains("0x16") ||
                        msg.contains("SCARD_E_SERVICE_STOPPED");

                if (isHarmless) {
                    throw new Exception(Constants.ERR_CARD_NOT_FOUND);
                } else {
                    killService();
                    throw new Exception(msg);
                }
            }

            if (response.startsWith("OK:")) return response.substring(3);

            throw new Exception("Protocol Error: " + response);

        } catch (Exception e) {
            if (!e.getMessage().contains(Constants.ERR_CARD_NOT_FOUND)) killService();
            throw e;
        }
    }

    @Override
    public void connect() throws Exception {
        try {
            String resp = sendCommand("CONNECT");
            if (!"CONNECTED".equals(resp)) throw new Exception("Connect failed: " + resp);
            isConnectedInternal = true;
        } catch (Exception e) {
            isConnectedInternal = false;
            throw e;
        }
    }

    @Override
    public void disconnect() {
        try {
            if (isConnected()) sendCommand("DISCONNECT");
        } catch (Exception ignored) {}
        isConnectedInternal = false;
    }

    private byte[] transmitInternal(CommandAPDU cmd) throws Exception {
        String b64Cmd = Base64.getEncoder().encodeToString(cmd.getBytes());
        String rawResp = sendCommand("TRANSMIT:" + b64Cmd);

        int splitIdx = rawResp.lastIndexOf('|');
        if (splitIdx == -1) throw new Exception("Invalid response: " + rawResp);

        String b64Data = rawResp.substring(0, splitIdx);
        int sw = Integer.parseInt(rawResp.substring(splitIdx + 1));

        if (sw != 0x9000) throw new Exception("Card Error SW: " + Integer.toHexString(sw));

        return b64Data.isEmpty() ? new byte[0] : Base64.getDecoder().decode(b64Data);
    }

    @Override
    public boolean verifyPin(byte[] pin) throws Exception {
        try {
            transmitInternal(new CommandAPDU(Constants.CLA_PROPRIETARY, Constants.OP_VERIFY_PIN, 0x00, 0x00, pin));
            return true;
        } catch (Exception e) {
            if (e.getMessage().contains("Card Error")) return false;
            throw e;
        }
    }

    @Override
    public boolean changePin(byte[] newPin) throws Exception {
        try {
            transmitInternal(new CommandAPDU(Constants.CLA_PROPRIETARY, Constants.OP_CHANGE_PIN, 0x00, 0x00, newPin));
            return true;
        } catch (Exception e) {
            if (e.getMessage().contains("Card Error")) return false;
            throw e;
        }
    }

    @Override
    public byte[] getPublicKey() throws Exception {
        return transmitInternal(new CommandAPDU(Constants.CLA_PROPRIETARY, Constants.OP_GET_PUBKEY, 0x00, 0x00));
    }

    @Override
    public byte[] getCertificate() throws Exception {
        return transmitInternal(new CommandAPDU(Constants.CLA_PROPRIETARY, Constants.OP_GET_CERT, 0x00, 0x00));
    }

    @Override
    public byte[] processGenesis() throws Exception {
        return transmitInternal(new CommandAPDU(Constants.CLA_PROPRIETARY, Constants.OP_GENESIS, 0x00, 0x00, new byte[0]));
    }

    @Override
    public byte[] processMint(byte[] payload) throws Exception {
        return transmitInternal(new CommandAPDU(Constants.CLA_PROPRIETARY, Constants.OP_MINT, 0x00, 0x00, payload));
    }

    @Override
    public byte[] processBurn(byte[] payload) throws Exception {
        return transmitInternal(new CommandAPDU(Constants.CLA_PROPRIETARY, Constants.OP_BURN, 0x00, 0x00, payload));
    }

    @Override
    public byte[] processSend(byte[] payload) throws Exception {
        return transmitInternal(new CommandAPDU(Constants.CLA_PROPRIETARY, Constants.OP_SEND, 0x00, 0x00, payload));
    }

    @Override
    public void processReceive(byte[] payload) throws Exception {
        transmitInternal(new CommandAPDU(Constants.CLA_PROPRIETARY, Constants.OP_RECEIVE, 0x00, 0x00, payload));
    }

    @Override
    public void addPeer(byte[] payload) throws Exception {
        transmitInternal(new CommandAPDU(Constants.CLA_PROPRIETARY, Constants.OP_ADD_PEER, 0x00, 0x00, payload));
    }

    @Override
    public boolean isPinSet() throws Exception {
        byte[] data = transmitInternal(new CommandAPDU(Constants.CLA_PROPRIETARY, Constants.OP_GET_STATUS, 0x00, 0x00, 256));
        return data.length > 0 && data[0] == (byte) 0x01;
    }

    @Override
    public boolean isGenesisDone() throws Exception {
        byte[] data = transmitInternal(new CommandAPDU(Constants.CLA_PROPRIETARY, Constants.OP_GET_STATUS, 0x00, 0x00, 256));
        return data.length > 1 && data[1] == (byte) 0x01;
    }
}