package dole.card;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.util.Log;
import dole.Constants;

public class AndroidSmartCard implements SmartCard {

    private static final String TAG = "AndroidSmartCard";
    private final IsoDep isoDep;
    private boolean isConnectedInternal = false;

    public AndroidSmartCard(Tag tag) throws IOException {
        this.isoDep = IsoDep.get(tag);
        if (this.isoDep == null) throw new IOException(Constants.ERR_CARD_NOT_FOUND);
    }

    @Override
    public boolean isConnected() {
        return isConnectedInternal && isoDep.isConnected();
    }

    @Override
    public void connect() throws IOException {
        if (!isoDep.isConnected()) {
            try {
                isoDep.connect();
                isoDep.setTimeout(5000);
            } catch (IOException e) {
                throw new IOException(Constants.ERR_CARD_NOT_FOUND);
            }
        }
        try {
            selectApplet();
            isConnectedInternal = true;
        } catch (Exception e) {
            disconnect();
            throw new IOException(Constants.ERR_CARD_NOT_FOUND);
        }
    }

    @Override
    public void disconnect() {
        try {
            if (isoDep.isConnected()) isoDep.close();
        } catch (IOException e) {
            Log.w(TAG, "Error closing IsoDep", e);
        }
        isConnectedInternal = false;
    }

    private byte[] transceiveSafe(byte[] command) throws Exception {
        try {
            return isoDep.transceive(command);
        } catch (IOException e) {
            throw new IOException(Constants.ERR_CARD_NOT_FOUND);
        }
    }

    private void selectApplet() throws Exception {
        byte[] command = buildApdu(0x00, 0xA4, 0x04, Constants.APPLET_AID_BYTES);
        byte[] response = transceiveSafe(command);
        checkStatusWord(response);
    }

    private byte[] transmit(int ins, byte[] data) throws Exception {
        if (!isConnected()) throw new IOException(Constants.ERR_CARD_NOT_FOUND);

        byte[] command = buildApdu(Constants.CLA_PROPRIETARY, ins, 0x00, data);
        byte[] response = transceiveSafe(command);

        checkStatusWord(response);

        if (response.length > 2) return Arrays.copyOf(response, response.length - 2); else return new byte[0];
    }

    private byte[] buildApdu(int cla, int ins, int p1, byte[] data) {
        int dataLen = (data != null) ? data.length : 0;
        ByteBuffer buf = ByteBuffer.allocate(5 + dataLen);
        buf.put((byte) cla);
        buf.put((byte) ins);
        buf.put((byte) p1);
        buf.put((byte) 0x00);
        buf.put((byte) dataLen);
        if (data != null) buf.put(data);
        return buf.array();
    }

    private void checkStatusWord(byte[] response) throws Exception {
        if (response == null || response.length < 2) throw new Exception("Invalid response length");
        int sw1 = response[response.length - 2] & 0xFF;
        int sw2 = response[response.length - 1] & 0xFF;
        int sw = (sw1 << 8) | sw2;
        if (sw != 0x9000) throw new Exception("Card Error SW: " + Integer.toHexString(sw));
    }

    @Override
    public boolean verifyPin(byte[] pin) {
        try {
            transmit(Constants.OP_VERIFY_PIN, pin);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Verify PIN failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean changePin(byte[] newPin) {
        try {
            transmit(Constants.OP_CHANGE_PIN, newPin);
            return true;
        } catch (Exception e) { return false; }
    }

    @Override
    public byte[] getPublicKey() throws Exception {
        return transmit(Constants.OP_GET_PUBKEY, null);
    }

    @Override
    public byte[] getCertificate() throws Exception {
        return transmit(Constants.OP_GET_CERT, null);
    }

    @Override
    public byte[] processGenesis() throws Exception {
        return transmit(Constants.OP_GENESIS, new byte[0]);
    }

    @Override
    public byte[] processMint(byte[] payload) throws Exception {
        return transmit(Constants.OP_MINT, payload);
    }

    @Override
    public byte[] processBurn(byte[] payload) throws Exception {
        return transmit(Constants.OP_BURN, payload);
    }

    @Override
    public byte[] processSend(byte[] payload) throws Exception {
        return transmit(Constants.OP_SEND, payload);
    }

    @Override
    public void processReceive(byte[] payload) throws Exception {
        transmit(Constants.OP_RECEIVE, payload);
    }

    @Override
    public void addPeer(byte[] payload) throws Exception {
        transmit(Constants.OP_ADD_PEER, payload);
    }

    @Override
    public boolean isPinSet() throws Exception {
        byte[] data = transmit(Constants.OP_GET_STATUS, null);
        return data.length > 0 && data[0] == (byte) 0x01;
    }

    @Override
    public boolean isGenesisDone() throws Exception {
        byte[] data = transmit(Constants.OP_GET_STATUS, null);
        return data.length > 1 && data[1] == (byte) 0x01;
    }

    @Override
    public int getPinRetries() throws Exception {
        byte[] data = transmit(Constants.OP_GET_STATUS, null);
        if (data.length > 2) return data[2];
        return 3;
    }
}