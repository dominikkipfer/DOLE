package card;

import javacard.framework.APDU;
import javacard.framework.Applet;
import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.JCSystem;
import javacard.framework.Util;

/**
 * Read-only NDEF Type 4 Tag applet containing a single URI record.
 * Used by iOS to trigger background reads via universal link. Android bypasses
 * NDEF and communicates with {@link Card} directly.
 */
public final class Ndef extends Applet {

    private static final byte INS_READ_BINARY = (byte) 0xB0;
    private static final byte P2_SELECT_BY_FILE_ID = (byte) 0x0C;
    private static final short FILE_ID_LENGTH = 2;

    private static final short FILE_ID_CC = (short) 0xE103;
    private static final short FILE_ID_NDEF = (short) 0xE104;

    private static final byte FILE_NONE = 0;
    private static final byte FILE_CC = 1;
    private static final byte FILE_NDEF = 2;

    /**
     * Capability Container file configuration (Mapping v2.0, 15 bytes, 246-byte frame length).
     * Defines NDEF file E104 with a maximum size of 64 bytes (read-only).
     */
    private static final byte[] CC_FILE = {
        0x00, 0x0F, 0x20, 0x00, (byte) 0xF6, 0x00, (byte) 0xF6,
        0x04, 0x06, (byte) 0xE1, 0x04, 0x00, 0x40, 0x00, (byte) 0xFF
    };

    /**
     * NDEF message layout (33 bytes total).
     * Contains a single well-known URI record with a 29-byte payload.
     */
    private static final byte[] NDEF_FILE = {
        0x00, 0x21, (byte) 0xD1, 0x01, 0x1D, 0x55, 0x04,
        'd', 'o', 'm', 'i', 'n', 'i', 'k', 'k', 'i', 'p', 'f', 'e', 'r',
        '.', 'g', 'i', 't', 'h', 'u', 'b', '.', 'i', 'o', '/', 'd', 'o', 'l', 'e'
    };

    /**
     * Currently selected file handle. Cleared on deselection.
     */
    private final byte[] selectedFile;

    /**
     * Installs the applet.
     */
    public static void install(byte[] bArray, short bOffset, byte bLength) {
        new Ndef(bArray, bOffset);
    }

    /**
     * Registers the applet under the install parameter AID to claim the NDEF AID.
     */
    private Ndef(byte[] bArray, short bOffset) {
        selectedFile = JCSystem.makeTransientByteArray((short) 1, JCSystem.CLEAR_ON_DESELECT);

        byte aidLen = bArray[bOffset];
        if (aidLen == 0) {
            register();
        } else {
            register(bArray, (short) (bOffset + 1), aidLen);
        }
    }

    /**
     * Main APDU processing loop.
     */
    public void process(APDU apdu) {
        if (selectingApplet()) {
            selectedFile[0] = FILE_NONE;
            return;
        }

        byte[] buffer = apdu.getBuffer();
        switch (buffer[ISO7816.OFFSET_INS]) {
            case ISO7816.INS_SELECT: processSelectFile(apdu, buffer); break;
            case INS_READ_BINARY:    processReadBinary(apdu, buffer); break;
            default: ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
        }
    }

    /**
     * Selects the Capability Container or NDEF file by its ID.
     */
    private void processSelectFile(APDU apdu, byte[] buffer) {
        if (buffer[ISO7816.OFFSET_P1] != 0x00 || buffer[ISO7816.OFFSET_P2] != P2_SELECT_BY_FILE_ID) {
            ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
        }
        if (apdu.setIncomingAndReceive() != FILE_ID_LENGTH) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        switch (Util.getShort(buffer, ISO7816.OFFSET_CDATA)) {
            case FILE_ID_CC:   selectedFile[0] = FILE_CC; break;
            case FILE_ID_NDEF: selectedFile[0] = FILE_NDEF; break;
            default: ISOException.throwIt(ISO7816.SW_FILE_NOT_FOUND);
        }
    }

    /**
     * Reads requested bytes from the selected file starting at the specified offset.
     */
    private void processReadBinary(APDU apdu, byte[] buffer) {
        if (selectedFile[0] == FILE_NONE) ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        byte[] file = selectedFile[0] == FILE_CC ? CC_FILE : NDEF_FILE;

        short offset = Util.getShort(buffer, ISO7816.OFFSET_P1);
        if (offset < 0 || offset > (short) file.length) ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);

        short requested = apdu.setOutgoing();
        short available = (short) (file.length - offset);
        short len = requested < available ? requested : available;

        apdu.setOutgoingLength(len);
        apdu.sendBytesLong(file, offset, len);
    }
}
