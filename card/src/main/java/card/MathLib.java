package card;

import dole.Constants;

/**
 * Unsigned 64-bit integer math for J3R180 (Java Card 3.0.5 with int support).
 * Operates on 8-byte big-endian arrays. HIGH = Bytes 0-3, LOW = Bytes 4-7.
 */
public class MathLib {

    private MathLib() {}

    private static int getInt(byte[] b, short off) {
        return ((b[off] & 0xFF) << 24)
                | ((b[(short)(off + 1)] & 0xFF) << 16)
                | ((b[(short)(off + 2)] & 0xFF) << 8)
                | (b[(short)(off + 3)] & 0xFF);
    }

    private static void setInt(byte[] b, short off, int val) {
        b[off] = (byte)(val >>> 24);
        b[(short)(off + 1)] = (byte)(val >>> 16);
        b[(short)(off + 2)] = (byte)(val >>> 8);
        b[(short)(off + 3)] = (byte)val;
    }

    /**
     * Unsigned 32-bit comparison.
     * @return true if a > b (unsigned)
     */
    private static boolean uintGt(int a, int b) {
        return (a ^ 0x80000000) > (b ^ 0x80000000);
    }

    /**
     * Adds a + b (unsigned 64-bit), result in res. Safe when res aliases a or b.
     * @return false on overflow past 2^64
     */
    public static boolean add(byte[] a, byte[] b, byte[] res) {
        int aLow  = getInt(a, (short)4);
        int bLow  = getInt(b, (short)4);
        int aHigh = getInt(a, (short)0);
        int bHigh = getInt(b, (short)0);

        int resLow = aLow + bLow;
        int carry = uintGt(aLow, resLow) ? 1 : 0;

        int sumHigh = aHigh + bHigh;
        int resHigh = sumHigh + carry;
        boolean overflow = uintGt(aHigh, sumHigh) || uintGt(sumHigh, resHigh);

        setInt(res, (short)4, resLow);
        setInt(res, (short)0, resHigh);

        return !overflow;
    }

    /**
     * Subtracts b from a (a - b), result in res. Precondition: a >= b.
     * Safe when res aliases a or b.
     */
    public static void subtract(byte[] a, byte[] b, byte[] res) {
        int aLow  = getInt(a, (short)4);
        int bLow  = getInt(b, (short)4);
        int aHigh = getInt(a, (short)0);
        int bHigh = getInt(b, (short)0);

        int resLow = aLow - bLow;
        int borrow = uintGt(bLow, aLow) ? 1 : 0;
        int resHigh = aHigh - bHigh - borrow;

        setInt(res, (short)4, resLow);
        setInt(res, (short)0, resHigh);
    }

    /**
     * Unsigned comparison of two 8-byte arrays.
     * @return 1 if a > b, -1 if a < b, 0 if equal
     */
    public static short compare(byte[] a, byte[] b) {
        int aHigh = getInt(a, (short)0);
        int bHigh = getInt(b, (short)0);

        if (uintGt(aHigh, bHigh)) return 1;
        if (uintGt(bHigh, aHigh)) return -1;

        int aLow = getInt(a, (short)4);
        int bLow = getInt(b, (short)4);

        if (uintGt(aLow, bLow)) return 1;
        if (uintGt(bLow, aLow)) return -1;

        return 0;
    }

    /**
     * @return true if all 8 bytes are zero
     */
    public static boolean isZero(byte[] a) {
        for (short i = 0; i < Constants.LONG_SIZE; i++) {
            if (a[i] != 0) return false;
        }
        return true;
    }

    /**
     * Increments 8-byte unsigned integer by one in-place. Wraps on overflow.
     */
    public static void increment(byte[] a) {
        int low = getInt(a, (short)4) + 1;
        setInt(a, (short)4, low);
        if (low == 0) {
            setInt(a, (short)0, getInt(a, (short)0) + 1);
        }
    }
}