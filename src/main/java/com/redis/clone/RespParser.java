package com.redis.clone;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A streaming parser for the RESP (REdis Serialization Protocol) v2.
 *
 * <p>Supports the five RESP data types:
 * <ul>
 *   <li><b>Simple Strings</b> — {@code +OK\r\n}</li>
 *   <li><b>Errors</b>          — {@code -ERR message\r\n}</li>
 *   <li><b>Integers</b>        — {@code :1000\r\n}</li>
 *   <li><b>Bulk Strings</b>    — {@code $6\r\nfoobar\r\n} (or {@code $-1\r\n} for null)</li>
 *   <li><b>Arrays</b>          — {@code *2\r\n$4\r\nPING\r\n$4\r\nPONG\r\n}</li>
 * </ul>
 *
 * <p>The parser reads directly from a {@link BufferedInputStream} so it works
 * correctly with binary-safe bulk strings (which may contain {@code \r\n}).
 *
 * <p><b>Thread-safety:</b> Each {@code RespParser} instance is bound to a
 * single socket stream and must only be used from one thread.
 */
public class RespParser implements AutoCloseable {

    private final BufferedInputStream in;

    public RespParser(InputStream inputStream) {
        // Wrap in BufferedInputStream if it isn't one already
        this.in = (inputStream instanceof BufferedInputStream)
                ? (BufferedInputStream) inputStream
                : new BufferedInputStream(inputStream);
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    // ---------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------

    /**
     * Parses the next RESP value from the stream.
     *
     * @return the parsed value (String, Long, List, or {@code null} for
     *         RESP Null Bulk Strings / Null Arrays), or {@code null} if
     *         the stream has ended (client disconnected).
     * @throws IOException on I/O or protocol errors
     */
    public Object parse() throws IOException {
        int typeByte = in.read();
        if (typeByte == -1) {
            return null; // stream ended — client disconnected
        }

        char type = (char) typeByte;

        return switch (type) {
            case '+' -> parseSimpleString();
            case '-' -> parseError();
            case ':' -> parseInteger();
            case '$' -> parseBulkString();
            case '*' -> parseArray();
            default  -> throw new IOException(
                    "Unknown RESP type byte: '" + type + "' (0x"
                            + Integer.toHexString(typeByte) + ")");
        };
    }

    /**
     * Convenience method that parses the next RESP value and expects it to
     * be an array of bulk strings — the format every Redis command arrives in.
     *
     * @return a {@code List<String>} representing the command + arguments,
     *         or {@code null} if the stream has ended.
     * @throws IOException on I/O or protocol errors
     */
    @SuppressWarnings("unchecked")
    public List<String> parseCommand() throws IOException {
        Object value = parse();
        if (value == null) {
            return null;
        }

        // Inline / plain-text commands (e.g. typing "PING" in telnet)
        if (value instanceof String s) {
            return List.of(s.trim().split("\\s+"));
        }

        if (value instanceof List<?> list) {
            // Convert every element to String for uniform handling
            List<String> parts = new ArrayList<>(list.size());
            for (Object element : list) {
                parts.add(element == null ? null : element.toString());
            }
            return parts;
        }

        throw new IOException("Expected RESP array for command, got: "
                + value.getClass().getSimpleName());
    }

    // ---------------------------------------------------------------
    // Internal parsers
    // ---------------------------------------------------------------

    /**
     * Reads a {@code +Simple String} value.
     * Format: {@code +OK\r\n}
     */
    private String parseSimpleString() throws IOException {
        return readLine();
    }

    /**
     * Reads a {@code -Error} value.  Errors are returned as plain Strings
     * prefixed with the error type.
     * Format: {@code -ERR unknown command\r\n}
     */
    private String parseError() throws IOException {
        return readLine();
    }

    /**
     * Reads a {@code :Integer} value.
     * Format: {@code :1000\r\n}
     */
    private Long parseInteger() throws IOException {
        String line = readLine();
        try {
            return Long.parseLong(line);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid RESP integer: '" + line + "'");
        }
    }

    /**
     * Reads a {@code $Bulk String} value.
     * Format: {@code $6\r\nfoobar\r\n} or {@code $-1\r\n} for null.
     */
    private String parseBulkString() throws IOException {
        int length = readIntLine();
        if (length == -1) {
            return null; // RESP Null Bulk String
        }
        if (length < 0) {
            throw new IOException("Invalid bulk string length: " + length);
        }

        // Read exactly `length` bytes (binary-safe)
        byte[] data = readExactBytes(length);

        // Consume the trailing \r\n
        expectCRLF();

        return new String(data, StandardCharsets.UTF_8);
    }

    /**
     * Reads a {@code *Array} value.
     * Format: {@code *2\r\n...elements...}
     */
    private List<Object> parseArray() throws IOException {
        int count = readIntLine();
        if (count == -1) {
            return null; // RESP Null Array
        }
        if (count < 0) {
            throw new IOException("Invalid array count: " + count);
        }

        List<Object> elements = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            elements.add(parse()); // recursive descent
        }
        return elements;
    }

    // ---------------------------------------------------------------
    // Low-level stream helpers
    // ---------------------------------------------------------------

    /**
     * Reads a line terminated by {@code \r\n} and returns it without the
     * terminator.  This is used for simple strings, errors, integer lines,
     * and length-prefix lines.
     */
    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        int prev = -1;
        int curr;

        while ((curr = in.read()) != -1) {
            if (prev == '\r' && curr == '\n') {
                // Remove the trailing \r that was already appended
                sb.setLength(sb.length() - 1);
                return sb.toString();
            }
            sb.append((char) curr);
            prev = curr;
        }

        // Stream ended before \r\n — treat as disconnect
        if (sb.isEmpty()) {
            throw new IOException("Unexpected end of stream while reading RESP line");
        }
        return sb.toString();
    }

    /**
     * Reads a line and parses it as an integer (used for bulk string
     * lengths and array counts).
     */
    private int readIntLine() throws IOException {
        String line = readLine();
        try {
            return Integer.parseInt(line);
        } catch (NumberFormatException e) {
            throw new IOException("Expected integer in RESP line, got: '" + line + "'");
        }
    }

    /**
     * Reads exactly {@code n} bytes from the stream.
     */
    private byte[] readExactBytes(int n) throws IOException {
        byte[] buf = new byte[n];
        int totalRead = 0;
        while (totalRead < n) {
            int read = in.read(buf, totalRead, n - totalRead);
            if (read == -1) {
                throw new IOException(
                        "Unexpected end of stream: expected " + n
                                + " bytes, got " + totalRead);
            }
            totalRead += read;
        }
        return buf;
    }

    /**
     * Reads and asserts the next two bytes are {@code \r\n}.
     */
    private void expectCRLF() throws IOException {
        int cr = in.read();
        int lf = in.read();
        if (cr != '\r' || lf != '\n') {
            throw new IOException(
                    "Expected \\r\\n after bulk string data, got: 0x"
                            + Integer.toHexString(cr) + " 0x"
                            + Integer.toHexString(lf));
        }
    }
}
