package provisioner;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.util.List;

import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import javax.smartcardio.TerminalFactory;

import dole.Constants;

/**
 * Provisions the smart card by acting as the Root CA.
 * <p>
 * It connects to the card, verifies the PIN, extracts the card's public key,
 * signs it with the Root CA private key, and installs the resulting certificate on the card.
 */
public class Provisioner {

    private static final String ROOT_PRIV_HEX = "31C4CD6E29F3B703DAC57D84C2D4FFC63BBC116917E101F41E44DC612787280D";

    /**
     * Application entry point.
     *
     * @param args Command line arguments (not used).
     */
    public static void main(String[] args) {
        try {
            TerminalFactory factory = TerminalFactory.getDefault();
            List<CardTerminal> terminals = factory.terminals().list();
            if (terminals.isEmpty()) throw new RuntimeException("No card terminal found.");

            Card card = terminals.getFirst().connect("*");
            CardChannel channel = card.getBasicChannel();

            checkSW(channel.transmit(new CommandAPDU(0x00, 0xA4, 0x04, 0x00, Constants.APPLET_AID_BYTES)), "Select Applet");

            ResponseAPDU keyResp = channel.transmit(new CommandAPDU(0x80, Constants.OP_GET_PUBKEY, 0x00, 0x00, 256));
            checkSW(keyResp, "Get Public Key");

            byte[] signature = sign(keyResp.getData());
            checkSW(channel.transmit(new CommandAPDU(0x80, Constants.OP_SET_CERT, 0x00, 0x00, signature)), "Set Certificate");

            card.disconnect(false);
            System.out.println("Provisioning successfully completed. Card is ready for setup in App.");
        } catch (Exception e) {
            System.err.println("Provisioning Error: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Validates the Status Word (SW) of an APDU response.
     * Throws a RuntimeException if the operation was not successful (0x9000).
     *
     * @param response  The APDU response from the card.
     * @param operation Name of the operation for error logging.
     */
    private static void checkSW(ResponseAPDU response, String operation) {
        if ((short) response.getSW() != Constants.SW_NO_ERROR) {
            throw new RuntimeException(String.format("%s failed. SW=0x%04X", operation, response.getSW()));
        }
    }

    /**
     * Signs data using ECDSA/SHA-256 with the hardcoded Root Private Key.
     *
     * @param data The data to sign (usually the card's public key).
     * @return The resulting signature bytes.
     * @throws Exception If crypto initialization fails.
     */
    private static byte[] sign(byte[] data) throws Exception {
        AlgorithmParameters params = AlgorithmParameters.getInstance(Constants.KEY_ALGORITHM);
        params.init(new ECGenParameterSpec(Constants.EC_CURVE));

        BigInteger s = new BigInteger(ROOT_PRIV_HEX, 16);
        ECPrivateKeySpec privateSpec = new ECPrivateKeySpec(s, params.getParameterSpec(ECParameterSpec.class));
        PrivateKey privateKey = KeyFactory.getInstance(Constants.KEY_ALGORITHM).generatePrivate(privateSpec);

        Signature ecdsa = Signature.getInstance(Constants.SIGNATURE_ALGORITHM);
        ecdsa.initSign(privateKey);
        ecdsa.update(data);
        return ecdsa.sign();
    }
}