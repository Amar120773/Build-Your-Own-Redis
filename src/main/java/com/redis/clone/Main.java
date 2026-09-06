package com.redis.clone;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Entry point for the Redis server clone.
 *
 * <p>Parses command-line arguments ({@code --port}, {@code --dir},
 * {@code --dbfilename}), loads an RDB snapshot if one exists, then
 * opens a TCP {@link ServerSocket} and continuously accepts incoming
 * client connections, delegating each to a {@link ClientHandler}
 * running on a dedicated thread.
 */
public class Main {

    public static void main(String[] args) {
        // ── 1. Parse CLI arguments ───────────────────────────────────
        ServerConfig config = ServerConfig.getInstance();
        config.parseArgs(args);

        int port = config.getPort();

        // ── 2. Load RDB file if configured ───────────────────────────
        String rdbPath = config.getRdbPath();
        if (rdbPath != null) {
            RdbFileReader reader = new RdbFileReader(RedisStore.getInstance());
            reader.load(rdbPath);
        }

        // ── 3. Start the TCP server ──────────────────────────────────
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            // SO_REUSEADDR lets us rebind quickly after a restart,
            // avoiding the "Address already in use" error from TIME_WAIT sockets.
            serverSocket.setReuseAddress(true);

            System.out.println("Redis server started. Listening on port " + port + "...");

            // Main accept loop — runs until the process is terminated
            while (true) {
                Socket clientSocket = serverSocket.accept();

                System.out.println("New connection accepted from "
                        + clientSocket.getRemoteSocketAddress());

                // Delegate the client session to a handler on its own thread
                ClientHandler handler = new ClientHandler(clientSocket);
                Thread clientThread = new Thread(handler);
                clientThread.setDaemon(true);   // don't block JVM shutdown
                clientThread.start();
            }

        } catch (IOException e) {
            System.err.println("Failed to start server on port " + port + ": " + e.getMessage());
            System.exit(1);
        }
    }
}
