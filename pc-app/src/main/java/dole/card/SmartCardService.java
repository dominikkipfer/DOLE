package dole.card;

import java.util.Base64;
import java.util.List;
import java.util.Scanner;

import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import javax.smartcardio.TerminalFactory;

import dole.Constants;

public class SmartCardService {
    private static Card terminalCard;
    private static CardChannel channel;

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("READY");

        while (scanner.hasNextLine()) {
            try {
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;
                if (line.equals("EXIT")) break;

                try {
                    if (line.equals("CONNECT")) doConnect();
                    else if (line.equals("DISCONNECT")) doDisconnect();
                    else if (line.startsWith("TRANSMIT:")) doTransmit(line.substring(9));
                    else System.out.println("ERROR:Unknown Command");
                } catch (Exception e) {
                    System.out.println("ERROR:" + e.getMessage());
                }
            } catch (Exception e) {
                break;
            }
        }
    }

    private static void cleanupSilent() {
        if (terminalCard != null) {
            try { terminalCard.disconnect(false); } catch(Exception ignored){}
        }
        terminalCard = null;
        channel = null;
    }

    private static void doConnect() throws Exception {
        if (terminalCard != null) cleanupSilent();

        TerminalFactory factory = TerminalFactory.getDefault();
        List<CardTerminal> terminals;
        try {
            terminals = factory.terminals().list();
        } catch (Exception e) {
            throw new Exception("DRIVER_CRASH:" + e.getMessage());
        }

        if (terminals.isEmpty()) throw new Exception(Constants.ERR_CODE_NO_READER);

        boolean found = false;
        for (CardTerminal t : terminals) {
            if (t.isCardPresent()) {
                try {
                    terminalCard = t.connect("*");
                    channel = terminalCard.getBasicChannel();

                    CommandAPDU select = new CommandAPDU(0x00, 0xA4, 0x04, 0x00, Constants.APPLET_AID_BYTES);
                    ResponseAPDU resp = channel.transmit(select);

                    if ((short)resp.getSW() == Constants.SW_NO_ERROR) {
                        found = true;
                        break;
                    }
                } catch (Exception e) {
                    cleanupSilent();
                }
            }
        }

        if (!found) {
            cleanupSilent();
            throw new Exception(Constants.ERR_CARD_NOT_FOUND);
        }
        System.out.println("OK:CONNECTED");
    }

    private static void doDisconnect() {
        cleanupSilent();
        System.out.println("OK:DISCONNECTED");
    }

    private static void doTransmit(String payload) throws Exception {
        if (channel == null) throw new Exception(Constants.ERR_CARD_NOT_FOUND);

        try {
            byte[] bytes = Base64.getDecoder().decode(payload);
            ResponseAPDU r = channel.transmit(new CommandAPDU(bytes));
            System.out.println("OK:" + Base64.getEncoder().encodeToString(r.getData()) + "|" + r.getSW());
        } catch (Exception e) {
            cleanupSilent();
            throw e;
        }
    }
}