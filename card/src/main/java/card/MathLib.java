package card;

/**
 * Helper class for 32-bit (4-byte) arithmetic on Java Card.
 */
public class MathLib {

    private MathLib() {}

    /**
     * Adds two 4-byte arrays (big-endian).
     * Stores the result in res.
     * @return false on overflow (if result > 2^31-1), otherwise true.
     */
    public static boolean addSafe(byte[] a, byte[] b, byte[] res) {
        short carry = 0;
        for (short i = 3; i >= 0; i--) {
            short vA = (short)(a[i] & 0xFF);
            short vB = (short)(b[i] & 0xFF);

            short val = (short)(vA + vB + carry);

            res[i] = (byte) val;

            if (val > 255) {
                carry = 1;
            } else {
                carry = 0;
            }
        }
        return (carry == 0) && ((res[0] & 0x80) == 0);
    }

    /**
     * Subtracts b from a (a - b).
     * Stores the result in res.
     * Assumes a >= b (no negative results).
     */
    public static void subtract(byte[] a, byte[] b, byte[] res) {
        short borrow = 0;
        for (short i = 3; i >= 0; i--) {
            short vA = (short)(a[i] & 0xFF);
            short vB = (short)(b[i] & 0xFF);

            short diff = (short)(vA - vB);
            diff = (short)(diff - borrow);

            if (diff < 0) {
                diff = (short)(diff + (short)256);
                borrow = 1;
            } else {
                borrow = 0;
            }
            res[i] = (byte) diff;
        }
    }

    /**
     * Compares two 4-byte arrays (big-endian).
     * @return 1 if a > b, -1 if a < b, 0 if equal.
     */
    public static short compare(byte[] a, byte[] b) {
        for (short i=0; i<4; i++) {
            short vA = (short)(a[i] & 0xFF);
            short vB = (short)(b[i] & 0xFF);

            if (vA > vB) return 1;
            if (vA < vB) return -1;
        }
        return 0;
    }

    /**
     * Checks whether the provided 4-byte big-endian array represents zero.
     * @param a a 4-byte big-endian array
     * @return true if all four bytes are zero, false otherwise
     */
    public static boolean isZero(byte[] a) {
        for (short i=0; i<4; i++) {
            if (a[i] != 0) return false;
        }
        return true;
    }

    /**
     * Increments a 4-byte big-endian array by one in-place.
     * The array is treated as an unsigned 32-bit integer.
     * If the increment carries past the most significant byte, the carry
     * is propagated and the array may wrap to zero.
     * @param a a 4-byte big-endian array to increment
     */
    public static void increment(byte[] a) {
        for (short i = 3; i >= 0; i--) {
            byte val = a[i];
            a[i] = (byte)(val + 1);

            if (a[i] != 0) return;
        }
    }
}