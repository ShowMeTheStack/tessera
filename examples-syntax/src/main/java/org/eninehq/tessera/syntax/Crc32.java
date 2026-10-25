package org.eninehq.tessera.syntax;

/** CRC-32 (IEEE 802.3) in plain Java with unsigned integers: no masks, no Integer.toUnsignedLong. */
public final class Crc32 {
    private Crc32() {}

    private static final uint POLY = 0xEDB88320u;

    public static uint update(uint crc, byte[] data) {
        crc = ~crc;
        for (byte b : data) {
            crc ^= (uint) (b & 0xFF);
            for (int k = 0; k < 8; k++)
                crc = crc[0] ? (crc >> 1) ^ POLY : crc >> 1;
        }
        return ~crc;
    }

    /** As a long, for comparison with java.util.zip.CRC32 (zero-extended, never negative). */
    public static long value(byte[] data) {
        uint c = update(0u, data);
        return c;
    }

    /** Unsigned arithmetic that would be wrong with int: average of two large values without overflow. */
    public static uint average(uint a, uint b) {
        return (a & b) + ((a ^ b) >> 1);
    }

    public static String describe(uint x) {
        return "value " + x + " (high nibble " + x[31:28] + ")";
    }
}
