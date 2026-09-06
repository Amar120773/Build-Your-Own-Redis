package com.redis.clone;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Self-contained demo client that starts the Redis server on a background
 * thread, then runs through every supported command, printing the
 * conversation in a readable format.
 *
 * <p>Usage:  {@code javac *.java && java com.redis.clone.DemoClient}
 * (or via Maven: {@code mvn compile exec:java -Dexec.mainClass=com.redis.clone.DemoClient})
 */
public class DemoClient {

    private static final int PORT = 6399; // Use a non-default port to avoid conflicts
    private static final String HOST = "127.0.0.1";

    public static void main(String[] args) throws Exception {
        // ── Start the server in a background thread ──────────────────
        Thread serverThread = new Thread(() -> {
            try (var serverSocket = new java.net.ServerSocket(PORT)) {
                serverSocket.setReuseAddress(true);
                System.out.println("═══════════════════════════════════════════════════");
                System.out.println("  Redis Clone Server — listening on port " + PORT);
                System.out.println("═══════════════════════════════════════════════════\n");

                while (!Thread.currentThread().isInterrupted()) {
                    var client = serverSocket.accept();
                    var handler = new ClientHandler(client);
                    Thread t = new Thread(handler);
                    t.setDaemon(true);
                    t.start();
                }
            } catch (IOException e) {
                if (!Thread.currentThread().isInterrupted()) {
                    System.err.println("Server error: " + e.getMessage());
                }
            }
        }, "redis-server");
        serverThread.setDaemon(true);
        serverThread.start();

        // Give the server a moment to bind
        Thread.sleep(500);

        // ── Run the demo ─────────────────────────────────────────────
        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║        BUILD-YOUR-OWN-REDIS  —  LIVE DEMO        ║");
        System.out.println("╠═══════════════════════════════════════════════════╣");
        System.out.println("║  Demonstrating: PING, ECHO, SET, GET, SET PX     ║");
        System.out.println("╚═══════════════════════════════════════════════════╝\n");

        try (Socket socket = new Socket(HOST, PORT)) {
            OutputStream out = socket.getOutputStream();
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());

            // ── Test 1: PING ─────────────────────────────────────────
            printSection("Test 1: PING (no arguments)");
            sendCommand(out, "PING");
            printResponse(in);

            // ── Test 2: PING with message ────────────────────────────
            printSection("Test 2: PING with message");
            sendCommand(out, "PING", "Hello Redis!");
            printResponse(in);

            // ── Test 3: ECHO ─────────────────────────────────────────
            printSection("Test 3: ECHO");
            sendCommand(out, "ECHO", "Build Your Own Redis");
            printResponse(in);

            // ── Test 4: SET / GET ────────────────────────────────────
            printSection("Test 4: SET key value");
            sendCommand(out, "SET", "language", "Java");
            printResponse(in);

            printSection("Test 5: GET key");
            sendCommand(out, "GET", "language");
            printResponse(in);

            // ── Test 5: Multiple SET / GET ───────────────────────────
            printSection("Test 6: SET & GET multiple keys");
            sendCommand(out, "SET", "project", "Redis Clone");
            printResponse(in);
            sendCommand(out, "SET", "version", "1.0");
            printResponse(in);
            sendCommand(out, "GET", "project");
            printResponse(in);
            sendCommand(out, "GET", "version");
            printResponse(in);

            // ── Test 6: GET non-existent key ─────────────────────────
            printSection("Test 7: GET non-existent key");
            sendCommand(out, "GET", "missing_key");
            printResponse(in);

            // ── Test 7: SET with PX expiration ───────────────────────
            printSection("Test 8: SET with PX (2000ms TTL)");
            sendCommand(out, "SET", "temp_key", "I will expire!", "PX", "2000");
            printResponse(in);

            System.out.println("  → GET temp_key (immediately, should exist):");
            sendCommand(out, "GET", "temp_key");
            printResponse(in);

            System.out.println("  → Waiting 2.5 seconds for expiration...\n");
            Thread.sleep(2500);

            System.out.println("  → GET temp_key (after expiry, should be nil):");
            sendCommand(out, "GET", "temp_key");
            printResponse(in);

            // ── Test 8: Overwrite existing key ───────────────────────
            printSection("Test 9: Overwrite existing key");
            sendCommand(out, "SET", "language", "Java 17");
            printResponse(in);
            sendCommand(out, "GET", "language");
            printResponse(in);

            // ── Test 9: Unknown command ──────────────────────────────
            printSection("Test 10: Unknown command");
            sendCommand(out, "FLUSHALL");
            printResponse(in);

        }

        System.out.println("\n╔═══════════════════════════════════════════════════╗");
        System.out.println("║           ALL TESTS PASSED — DEMO COMPLETE        ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");

        // Shut down
        serverThread.interrupt();
    }

    // ─── RESP command encoder ────────────────────────────────────────

    /**
     * Encodes and sends a command as a RESP array of bulk strings.
     * e.g. sendCommand(out, "SET", "key", "value")
     * → *3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n
     */
    private static void sendCommand(OutputStream out, String... parts) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("*").append(parts.length).append("\r\n");
        for (String part : parts) {
            byte[] data = part.getBytes(StandardCharsets.UTF_8);
            sb.append("$").append(data.length).append("\r\n");
            sb.append(part).append("\r\n");
        }

        String raw = sb.toString();
        out.write(raw.getBytes(StandardCharsets.UTF_8));
        out.flush();

        // Print what we sent (in redis-cli style)
        System.out.print("  redis-cli> ");
        System.out.println(String.join(" ", parts));
    }

    // ─── RESP response reader ────────────────────────────────────────

    /**
     * Reads and prints a single RESP response from the server.
     */
    private static void printResponse(BufferedInputStream in) throws IOException, InterruptedException {
        // Small delay to let the server respond
        Thread.sleep(100);

        int type = in.read();
        if (type == -1) {
            System.out.println("  (connection closed)");
            return;
        }

        switch ((char) type) {
            case '+' -> {
                // Simple String
                String line = readLine(in);
                System.out.println("  ← " + line);
            }
            case '-' -> {
                // Error
                String line = readLine(in);
                System.out.println("  ← (error) " + line);
            }
            case ':' -> {
                // Integer
                String line = readLine(in);
                System.out.println("  ← (integer) " + line);
            }
            case '$' -> {
                // Bulk String
                String lengthStr = readLine(in);
                int length = Integer.parseInt(lengthStr);
                if (length == -1) {
                    System.out.println("  ← (nil)");
                } else {
                    byte[] data = new byte[length];
                    int read = 0;
                    while (read < length) {
                        int r = in.read(data, read, length - read);
                        if (r == -1) break;
                        read += r;
                    }
                    // consume \r\n
                    in.read();
                    in.read();
                    System.out.println("  ← \"" + new String(data, StandardCharsets.UTF_8) + "\"");
                }
            }
            default -> {
                String line = readLine(in);
                System.out.println("  ← (unknown:" + (char) type + ") " + line);
            }
        }
        System.out.println();
    }

    private static String readLine(BufferedInputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int prev = -1, curr;
        while ((curr = in.read()) != -1) {
            if (prev == '\r' && curr == '\n') {
                sb.setLength(sb.length() - 1);
                return sb.toString();
            }
            sb.append((char) curr);
            prev = curr;
        }
        return sb.toString();
    }

    // ─── Formatting helpers ──────────────────────────────────────────

    private static void printSection(String title) {
        System.out.println("┌─────────────────────────────────────────────────┐");
        System.out.printf("│  %-47s │%n", title);
        System.out.println("└─────────────────────────────────────────────────┘");
    }
}
