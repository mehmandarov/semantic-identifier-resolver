package utils;

import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

// Inspired by: https://raw.githubusercontent.com/rootsdev/polygenea/master/java/src/org/rootsdev/polygenea/UUID5.java
/**
 * This class contains static methods that leverage {@link java.util.UUID} and
 * {@link java.security.MessageDigest} to create version-5 UUIDs with full
 * namespace support.
 * <p>
 * The UUID class provided by java.util is suitable as a datatype for UUIDs of
 * any version, but lacks methods for creating version 5 (SHA-1 based) UUIDs.
 * Its implementation of version 3 (MD5 based) UUIDs also lacks build-in
 * namespace support.
 * <p>
 * This class was informed by <a href="http://www.ietf.org/rfc/rfc4122.txt">RFC
 * 4122</a>. Since RFC 4122 is vague on how a 160-bit hash is turned into the
 * 122 free bits of a UUID (6 bits being used for version and variant
 * information), this class was modelled after java.util.UUID's type-3
 * implementation and validated against the D language's phobos library <a
 * href="http://dlang.org/phobos/std_uuid.html">std.uuid</a>, which in turn was
 * modelled after the Boost project's <a
 * href="http://www.boost.org/doc/libs/1_42_0/libs/uuid/uuid.html"
 * >boost.uuid</a>; and also validated against the Python language's <a
 * href="http://docs.python.org/2/library/uuid.html">uuid</a> library.
 *
 * @see java.util.UUID
 * @see java.security.MessageDigest
 *
 * @author Luther Tychonievich. Released into the public domain. I would
 *         consider it a courtesy if you cite me if you benefit from this code.
 */
public class UUIDv5 {

    /**
     * Similar to UUID.nameUUIDFromBytes, but does version 5 (sha-1) not version
     * 3 (md5)
     *
     * @param name
     *            The bytes to use as the "name" of this hash
     * @return the UUID object
     */
    public static UUID fromBytes(byte[] name) {
        if (name == null) {
            throw new NullPointerException("name == null");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return makeUUID(md.digest(name), 5);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Similar to UUID.nameUUIDFromBytes, but does version 5 (sha-1) not version
     * 3 (md5)
     *
     * @param name
     *            The string to be encoded in utf-8 to get the bytes to hash
     * @return the UUID object
     */
    public static UUID fromUTF8(String name) {
        return UUIDv5.fromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A helper method for making uuid objects, which in java store longs not
     * bytes
     *
     * @param src
     *            An array of bytes having at least offset+8 elements
     * @param offset
     *            Where to start extracting a long
     * @param order
     *            either ByteOrder.BIG_ENDIAN or ByteOrder.LITTLE_ENDIAN
     * @return a long, the specified endianness of which matches the bytes in
     *         src[offset,offset+8]
     */
    static long peekLong(final byte[] src, final int offset, final ByteOrder order) {
        long ans = 0;
        if (order == ByteOrder.BIG_ENDIAN) {
            for (int i = offset; i < offset + 8; i += 1) {
                ans <<= 8;
                ans |= src[i] & 0xffL;
            }
        } else {
            for (int i = offset + 7; i >= offset; i -= 1) {
                ans <<= 8;
                ans |= src[i] & 0xffL;
            }
        }
        return ans;
    }

    /**
     * A private method from UUID pulled out here, so we have access to it.
     *
     * @param hash
     *            A 16 (or more) byte array to be the basis of the UUID
     * @param version
     *            The version number to replace 4 bits of the hash (the variant
     *            code will replace 2 more bits))
     * @return A UUID object
     */
    static UUID makeUUID(byte[] hash, int version) {
        long msb = peekLong(hash, 0, ByteOrder.BIG_ENDIAN);
        long lsb = peekLong(hash, 8, ByteOrder.BIG_ENDIAN);
        // Set the version field
        msb &= ~(0xfL << 12);
        msb |= ((long) version) << 12;
        // Set the variant field to 2
        lsb &= ~(0x3L << 62);
        lsb |= 2L << 62;
        return new UUID(msb, lsb);
    }
}