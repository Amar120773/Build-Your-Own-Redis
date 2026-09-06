package com.redis.clone;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Routes parsed RESP commands to the appropriate handler logic and writes
 * the RESP-encoded response back to the client.
 *
 * <p>Currently supported commands:
 * <ul>
 *   <li><b>PING</b> [message]  — returns {@code +PONG} or bulk-string echo</li>
 *   <li><b>ECHO</b> message    — returns the message as a bulk string</li>
 *   <li><b>SET</b> key value [PX ms] — stores a key-value pair with optional TTL</li>
 *   <li><b>GET</b> key          — retrieves a value (or null if missing/expired)</li>
 *   <li><b>CONFIG GET</b> param — returns server configuration values</li>
 *   <li><b>KEYS</b> pattern     — returns all keys matching the pattern</li>
 * </ul>
 */
public class CommandRouter {

    private final ServerConfig config = ServerConfig.getInstance();

    private final RedisStore store = RedisStore.getInstance();

    /**
     * Dispatches a parsed command (list of strings) and writes the
     * appropriate RESP response to {@code out}.
     *
     * @param command the parsed command parts (command name + arguments)
     * @param out     the client's output stream
     * @throws IOException on write failures
     */
    public void dispatch(List<String> command, OutputStream out) throws IOException {
        if (command == null || command.isEmpty()) {
            writeError(out, "ERR empty command");
            return;
        }

        // Redis commands are case-insensitive
        String commandName = command.get(0).toUpperCase();

        switch (commandName) {
            case "PING" -> handlePing(command, out);
            case "ECHO" -> handleEcho(command, out);
            case "SET"  -> handleSet(command, out);
            case "GET"    -> handleGet(command, out);
            case "CONFIG" -> handleConfig(command, out);
            case "KEYS"   -> handleKeys(command, out);
            default       -> writeError(out,
                    "ERR unknown command '" + command.get(0)
                            + "', with args beginning with: "
                            + formatArgs(command));
        }
    }

    // ---------------------------------------------------------------
    // Command handlers
    // ---------------------------------------------------------------

    /**
     * PING [message]
     *
     * <ul>
     *   <li>No arguments → responds with {@code +PONG\r\n}</li>
     *   <li>With argument → responds with a bulk string of the argument</li>
     * </ul>
     */
    private void handlePing(List<String> command, OutputStream out) throws IOException {
        if (command.size() > 2) {
            writeError(out, "ERR wrong number of arguments for 'ping' command");
            return;
        }

        if (command.size() == 1) {
            writeSimpleString(out, "PONG");
        } else {
            writeBulkString(out, command.get(1));
        }
    }

    /**
     * ECHO message
     *
     * Returns the argument as a RESP bulk string.
     */
    private void handleEcho(List<String> command, OutputStream out) throws IOException {
        if (command.size() != 2) {
            writeError(out, "ERR wrong number of arguments for 'echo' command");
            return;
        }

        writeBulkString(out, command.get(1));
    }

    /**
     * SET key value [PX milliseconds]
     *
     * <p>Stores the key-value pair.  If the optional {@code PX} flag is
     * provided, the key will automatically expire after the specified
     * number of milliseconds.
     *
     * <p>Examples:
     * <pre>
     *   SET mykey myvalue            → stores with no expiry
     *   SET mykey myvalue PX 5000    → expires in 5 seconds
     * </pre>
     */
    private void handleSet(List<String> command, OutputStream out) throws IOException {
        // SET key value [PX ms]  →  minimum 3 args, maximum 5
        if (command.size() < 3) {
            writeError(out, "ERR wrong number of arguments for 'set' command");
            return;
        }

        String key   = command.get(1);
        String value = command.get(2);

        // Parse optional flags (PX)
        int i = 3;
        while (i < command.size()) {
            String flag = command.get(i).toUpperCase();

            if ("PX".equals(flag)) {
                if (i + 1 >= command.size()) {
                    writeError(out, "ERR syntax error");
                    return;
                }
                long ttlMs;
                try {
                    ttlMs = Long.parseLong(command.get(i + 1));
                } catch (NumberFormatException e) {
                    writeError(out, "ERR value is not an integer or out of range");
                    return;
                }
                if (ttlMs <= 0) {
                    writeError(out, "ERR invalid expire time in 'set' command");
                    return;
                }
                store.set(key, value, ttlMs);
                writeSimpleString(out, "OK");
                return;
            } else {
                writeError(out, "ERR syntax error");
                return;
            }
        }

        // No optional flags — store without expiry
        store.set(key, value);
        writeSimpleString(out, "OK");
    }

    /**
     * GET key
     *
     * <p>Returns the value stored at {@code key}, or a RESP Null Bulk
     * String if the key does not exist or has expired.
     */
    private void handleGet(List<String> command, OutputStream out) throws IOException {
        if (command.size() != 2) {
            writeError(out, "ERR wrong number of arguments for 'get' command");
            return;
        }

        String value = store.get(command.get(1));
        writeBulkString(out, value); // writes $-1\r\n when value is null
    }

    /**
     * CONFIG GET parameter
     *
     * <p>Returns the requested configuration parameter and its value
     * as a RESP array of bulk strings.  Supports {@code dir} and
     * {@code dbfilename}.
     */
    private void handleConfig(List<String> command, OutputStream out) throws IOException {
        if (command.size() < 2) {
            writeError(out, "ERR wrong number of arguments for 'config' command");
            return;
        }

        String subCommand = command.get(1).toUpperCase();

        if ("GET".equals(subCommand)) {
            if (command.size() != 3) {
                writeError(out, "ERR wrong number of arguments for 'config|get' command");
                return;
            }

            String pattern = command.get(2);
            Map<String, String> values = config.getConfigValues(pattern);

            // Write as RESP array: [key1, value1, key2, value2, ...]
            writeArrayHeader(out, values.size() * 2);
            for (Map.Entry<String, String> entry : values.entrySet()) {
                writeBulkString(out, entry.getKey());
                writeBulkString(out, entry.getValue());
            }
        } else {
            writeError(out, "ERR unknown subcommand '" + command.get(1)
                    + "'. Try CONFIG GET.");
        }
    }

    /**
     * KEYS pattern
     *
     * <p>Returns all keys matching the given pattern as a RESP array.
     * Currently only supports {@code *} (match all).
     */
    private void handleKeys(List<String> command, OutputStream out) throws IOException {
        if (command.size() != 2) {
            writeError(out, "ERR wrong number of arguments for 'keys' command");
            return;
        }

        String pattern = command.get(1);
        Set<String> keys = store.getKeys(pattern);

        writeArrayHeader(out, keys.size());
        for (String key : keys) {
            writeBulkString(out, key);
        }
    }

    // ---------------------------------------------------------------
    // RESP response encoders
    // ---------------------------------------------------------------

    /**
     * Writes a RESP Simple String: {@code +<message>\r\n}
     */
    public static void writeSimpleString(OutputStream out, String message) throws IOException {
        out.write(("+" + message + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * Writes a RESP Error: {@code -<message>\r\n}
     */
    public static void writeError(OutputStream out, String message) throws IOException {
        out.write(("-" + message + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * Writes a RESP Integer: {@code :<value>\r\n}
     */
    public static void writeInteger(OutputStream out, long value) throws IOException {
        out.write((":" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * Writes a RESP Bulk String: {@code $<len>\r\n<data>\r\n}
     * or a Null Bulk String: {@code $-1\r\n} if {@code value} is null.
     */
    public static void writeBulkString(OutputStream out, String value) throws IOException {
        if (value == null) {
            out.write("$-1\r\n".getBytes(StandardCharsets.UTF_8));
        } else {
            byte[] data = value.getBytes(StandardCharsets.UTF_8);
            out.write(("$" + data.length + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(data);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.flush();
    }

    /**
     * Writes a RESP Null Bulk String: {@code $-1\r\n}
     */
    public static void writeNullBulkString(OutputStream out) throws IOException {
        writeBulkString(out, null);
    }

    /**
     * Writes a RESP Array header: {@code *<count>\r\n}
     *
     * <p>The caller is responsible for writing exactly {@code count}
     * elements after this header.
     */
    public static void writeArrayHeader(OutputStream out, int count) throws IOException {
        out.write(("*" + count + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /**
     * Formats command arguments (indices 1+) for error messages, mimicking
     * real Redis error output.
     */
    private String formatArgs(List<String> command) {
        if (command.size() <= 1) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < command.size(); i++) {
            if (i > 1) sb.append(' ');
            sb.append('\'').append(command.get(i)).append('\'');
        }
        return sb.toString();
    }
}
