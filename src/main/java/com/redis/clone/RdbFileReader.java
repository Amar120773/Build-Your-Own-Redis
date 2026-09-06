package com.redis.clone;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Parses a Redis RDB (Redis Database) binary file and loads keys, values,
 * and expiration timestamps into the {@link RedisStore}.
 *
 * <h3>RDB File Structure (simplified)</h3>
 * <pre>
 * ┌──────────────────────────────────────────────┐
 * │  Header:  "REDIS" (5 bytes) + version (4 bytes)  │
 * ├──────────────────────────────────────────────┤
 * │  Sections (repeating):                            │
 * │    0xFA  — Auxiliary field (key-value metadata)    │
 * │    0xFE  — Database selector (db number)          │
 * │    0xFB  — Resize-DB (hash table sizes)           │
 * │    0xFC  — Expire time in milliseconds (8 bytes LE) │
 * │    0xFD  — Expire time in seconds (4 bytes LE)    │
 * │    0xFF  — End of file                            │
 * │    0x00  — String value type                      │
 * ├──────────────────────────────────────────────┤
 * │  8-byte checksum (CRC64)                          │
 * └──────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>Length Encoding</h3>
 *
 * <p>Redis uses a variable-length encoding scheme. The first two bits of
 * the first byte determine how to read the length:
 *
 * <pre>
 * Bits 7-6 │ Meaning
 * ─────────┼───────────────────────────────────────────
 *   0b00   │  6-bit length: remaining 6 bits of this byte
 *   0b01   │ 14-bit length: remaining 6 bits + next byte
 *   0b10   │ 32-bit length: next 4 bytes (big-endian)
 *   0b11   │ Special encoding (integer-as-string):
 *          │   format = lower 6 bits:
 *          │     0 → 8-bit integer
 *          │     1 → 16-bit integer (LE)
 *          │     2 → 32-bit integer (LE)
 * </pre>
 *
 * <p>This class parses all four forms correctly.
 */
public class RdbFileReader {

    // ── RDB op-code constants ────────────────────────────────────────
    private static final int OP_AUX          = 0xFA;
    private static final int OP_SELECTDB     = 0xFE;
    private static final int OP_RESIZEDB     = 0xFB;
    private static final int OP_EXPIRETIME_MS = 0xFC;
    private static final int OP_EXPIRETIME_S  = 0xFD;
    private static final int OP_EOF          = 0xFF;

    // ── Value type constants ─────────────────────────────────────────
    private static final int VALUE_TYPE_STRING = 0;

    private final RedisStore store;

    public RdbFileReader(RedisStore store) {
        this.store = store;
    }

    // ---------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------

    /**
     * Loads the RDB file at {@code path} into the store.
     *
     * <p>If the file does not exist, this method returns silently
     * (the server starts with an empty dataset).
     *
     * @param path absolute path to the RDB file
     */
    public void load(String path) {
        Path filePath = Path.of(path);
        if (!Files.exists(filePath)) {
            System.out.println("RDB file not found: " + path + " — starting with empty dataset.");
            return;
        }

        try (InputStream raw = new FileInputStream(path);
             BufferedInputStream in = new BufferedInputStream(raw)) {

            readHeader(in);
            readSections(in);

            System.out.println("RDB file loaded: " + path
                    + " (" + store.size() + " keys)");

        } catch (IOException e) {
            System.err.println("Error reading RDB file: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------
    // Header
    // ---------------------------------------------------------------

    /**
     * Reads and validates the 9-byte header:
     * {@code REDIS} (5 bytes) + version number (4 ASCII digits).
     */
    private void readHeader(InputStream in) throws IOException {
        byte[] magic = readExact(in, 5);
        String magicStr = new String(magic, StandardCharsets.US_ASCII);
        if (!"REDIS".equals(magicStr)) {
            throw new IOException("Invalid RDB magic: '" + magicStr + "'");
        }

        byte[] versionBytes = readExact(in, 4);
        String version = new String(versionBytes, StandardCharsets.US_ASCII);
        System.out.println("RDB version: " + version);
    }

    // ---------------------------------------------------------------
    // Section loop
    // ---------------------------------------------------------------

    /**
     * Main parsing loop — reads op-codes and dispatches to the
     * appropriate handler until EOF ({@code 0xFF}) is encountered.
     */
    private void readSections(InputStream in) throws IOException {
        while (true) {
            int opCode = in.read();
            if (opCode == -1 || opCode == OP_EOF) {
                break; // end of RDB payload
            }

            switch (opCode) {
                case OP_AUX -> readAuxField(in);
                case OP_SELECTDB -> readSelectDb(in);
                case OP_RESIZEDB -> readResizeDb(in);
                case OP_EXPIRETIME_MS -> readKeyValueWithExpiry(in, readUnsignedLittleEndian8(in));
                case OP_EXPIRETIME_S -> readKeyValueWithExpiry(in, readUnsignedLittleEndian4(in) * 1000L);
                default -> {
                    // `opCode` is actually the value-type byte for a
                    // key-value pair with no expiry
                    readKeyValue(in, opCode, StoreEntry.NO_EXPIRY);
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // Section handlers
    // ---------------------------------------------------------------

    /**
     * Reads an auxiliary metadata field (key + value), both length-
     * encoded strings.  We log them but don't act on them.
     */
    private void readAuxField(InputStream in) throws IOException {
        String auxKey = readLengthEncodedString(in);
        String auxValue = readLengthEncodedString(in);
        System.out.println("  RDB aux: " + auxKey + " = " + auxValue);
    }

    /**
     * Reads the database selector (db index).
     * We only support db 0, so we just consume the byte.
     */
    private void readSelectDb(InputStream in) throws IOException {
        int dbIndex = readLength(in).length;
        System.out.println("  RDB selecting DB " + dbIndex);
    }

    /**
     * Reads the resize-db indicator — two length-encoded integers
     * representing the hash-table and expire-table sizes.
     */
    private void readResizeDb(InputStream in) throws IOException {
        int hashTableSize = readLength(in).length;
        int expireTableSize = readLength(in).length;
        System.out.println("  RDB resize-db: hash=" + hashTableSize
                + ", expire=" + expireTableSize);
    }

    /**
     * Reads a key-value pair that was preceded by an expiry op-code.
     * The expiry timestamp (absolute epoch-millis) has already been read.
     */
    private void readKeyValueWithExpiry(InputStream in, long expiresAtMillis) throws IOException {
        int valueType = in.read();
        if (valueType == -1) {
            throw new IOException("Unexpected end of stream after expiry op-code");
        }
        readKeyValue(in, valueType, expiresAtMillis);
    }

    /**
     * Reads a single key-value pair and inserts it into the store.
     *
     * @param in              the input stream
     * @param valueType       the value-type byte (currently only 0 = string)
     * @param expiresAtMillis absolute epoch-millis, or {@link StoreEntry#NO_EXPIRY}
     */
    private void readKeyValue(InputStream in, int valueType, long expiresAtMillis) throws IOException {
        if (valueType != VALUE_TYPE_STRING) {
            throw new IOException("Unsupported RDB value type: " + valueType
                    + " (only string type 0 is supported)");
        }

        String key = readLengthEncodedString(in);
        String value = readLengthEncodedString(in);

        // Insert into the store with the absolute expiration timestamp
        store.setAbsolute(key, value, expiresAtMillis);
    }

    // ---------------------------------------------------------------
    // Length Encoding
    // ---------------------------------------------------------------

    /**
     * Result of a length-encoding read.  If {@link #isSpecialEncoding}
     * is {@code true}, the "length" is actually a special-format
     * indicator and the value has already been fully decoded.
     */
    private record LengthResult(int length, boolean isSpecialEncoding) {}

    /**
     * Reads a Redis length-encoded integer from the stream.
     *
     * <p><b>Encoding scheme (first byte):</b>
     * <pre>
     *  Bits 7-6 │ Meaning
     *  ─────────┼──────────────────────────────────────────
     *    00      │ The next 6 bits represent the length
     *    01      │ Read one additional byte.  The combined
     *           │ 14 bits (6 from first + 8 from second)
     *           │ represent the length.
     *    10      │ Discard the remaining 6 bits.  The next
     *           │ 4 bytes (big-endian) represent the length.
     *    11      │ Special format.  The next 6 bits indicate
     *           │ the encoding type (0=int8, 1=int16LE,
     *           │ 2=int32LE).  Not a "length" at all.
     * </pre>
     *
     * @return a {@link LengthResult} containing the decoded length and
     *         whether it used special encoding
     */
    private LengthResult readLength(InputStream in) throws IOException {
        int firstByte = readUnsignedByte(in);
        int type = (firstByte >> 6) & 0x03;  // top 2 bits

        return switch (type) {
            case 0 -> {
                // 6-bit length
                int length = firstByte & 0x3F;
                yield new LengthResult(length, false);
            }
            case 1 -> {
                // 14-bit length: 6 bits from first byte + 8 bits from second
                int secondByte = readUnsignedByte(in);
                int length = ((firstByte & 0x3F) << 8) | secondByte;
                yield new LengthResult(length, false);
            }
            case 2 -> {
                // 32-bit length: next 4 bytes, big-endian
                byte[] buf = readExact(in, 4);
                int length = ((buf[0] & 0xFF) << 24)
                           | ((buf[1] & 0xFF) << 16)
                           | ((buf[2] & 0xFF) << 8)
                           |  (buf[3] & 0xFF);
                yield new LengthResult(length, false);
            }
            case 3 -> {
                // Special encoding — the lower 6 bits encode the format
                int format = firstByte & 0x3F;
                yield new LengthResult(format, true);
            }
            default -> throw new IOException("Impossible length-encoding type: " + type);
        };
    }

    /**
     * Reads a length-encoded string from the stream.
     *
     * <p>If the length encoding indicates a special format (top 2 bits
     * = {@code 0b11}), the value is decoded as an integer stored in
     * 1, 2, or 4 bytes and returned as its string representation.
     *
     * <p>Otherwise, the length is read first, then exactly that many
     * bytes are read as the raw string content.
     */
    private String readLengthEncodedString(InputStream in) throws IOException {
        LengthResult lr = readLength(in);

        if (lr.isSpecialEncoding()) {
            // Integer-as-string: format is in lr.length()
            return switch (lr.length()) {
                case 0 -> {
                    // 8-bit signed integer
                    int val = readUnsignedByte(in);
                    yield String.valueOf((byte) val);
                }
                case 1 -> {
                    // 16-bit signed integer, little-endian
                    byte[] buf = readExact(in, 2);
                    int val = (buf[0] & 0xFF) | ((buf[1] & 0xFF) << 8);
                    yield String.valueOf((short) val);
                }
                case 2 -> {
                    // 32-bit signed integer, little-endian
                    byte[] buf = readExact(in, 4);
                    int val = (buf[0] & 0xFF)
                            | ((buf[1] & 0xFF) << 8)
                            | ((buf[2] & 0xFF) << 16)
                            | ((buf[3] & 0xFF) << 24);
                    yield String.valueOf(val);
                }
                default -> throw new IOException(
                        "Unknown special string encoding format: " + lr.length());
            };
        }

        // Normal string — read exactly `length` bytes
        byte[] data = readExact(in, lr.length());
        return new String(data, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------
    // Low-level binary helpers
    // ---------------------------------------------------------------

    /**
     * Reads a single byte as an unsigned value (0–255).
     */
    private int readUnsignedByte(InputStream in) throws IOException {
        int b = in.read();
        if (b == -1) {
            throw new IOException("Unexpected end of RDB stream");
        }
        return b;
    }

    /**
     * Reads exactly {@code n} bytes from the stream.
     */
    private byte[] readExact(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int totalRead = 0;
        while (totalRead < n) {
            int read = in.read(buf, totalRead, n - totalRead);
            if (read == -1) {
                throw new IOException("Unexpected end of RDB stream: "
                        + "expected " + n + " bytes, got " + totalRead);
            }
            totalRead += read;
        }
        return buf;
    }

    /**
     * Reads an 8-byte unsigned little-endian integer.
     * Used for millisecond expiry timestamps ({@code 0xFC}).
     *
     * <p>Redis stores these as unsigned 64-bit LE values.  Java's
     * {@code long} is signed, but the epoch-millis values we care
     * about fit well within the positive range of a signed long.
     */
    private long readUnsignedLittleEndian8(InputStream in) throws IOException {
        byte[] buf = readExact(in, 8);
        return  (buf[0] & 0xFFL)
              | ((buf[1] & 0xFFL) << 8)
              | ((buf[2] & 0xFFL) << 16)
              | ((buf[3] & 0xFFL) << 24)
              | ((buf[4] & 0xFFL) << 32)
              | ((buf[5] & 0xFFL) << 40)
              | ((buf[6] & 0xFFL) << 48)
              | ((buf[7] & 0xFFL) << 56);
    }

    /**
     * Reads a 4-byte unsigned little-endian integer.
     * Used for second-precision expiry timestamps ({@code 0xFD}).
     *
     * <p>Returned as a {@code long} to avoid signed-int overflow for
     * timestamps after 2038.
     */
    private long readUnsignedLittleEndian4(InputStream in) throws IOException {
        byte[] buf = readExact(in, 4);
        return  (buf[0] & 0xFFL)
              | ((buf[1] & 0xFFL) << 8)
              | ((buf[2] & 0xFFL) << 16)
              | ((buf[3] & 0xFFL) << 24);
    }
}
