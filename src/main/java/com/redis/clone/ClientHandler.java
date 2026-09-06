package com.redis.clone;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.List;

/**
 * Handles a single client connection on a dedicated thread.
 *
 * <p>Uses {@link RespParser} to deserialize incoming RESP commands and
 * {@link CommandRouter} to dispatch them, writing the RESP-encoded
 * response back to the client.
 *
 * <p>The handler runs in a loop until the client disconnects or an
 * unrecoverable I/O error occurs.
 */
public class ClientHandler implements Runnable {

    private final Socket clientSocket;
    private final CommandRouter router;

    public ClientHandler(Socket clientSocket) {
        this.clientSocket = clientSocket;
        this.router = new CommandRouter();
    }

    @Override
    public void run() {
        String clientAddr = clientSocket.getRemoteSocketAddress().toString();

        try (
            // RespParser wraps the raw InputStream in a BufferedInputStream
            RespParser parser = buildParser();
            OutputStream out = new BufferedOutputStream(clientSocket.getOutputStream())
        ) {
            List<String> command;

            // Read-parse-dispatch loop
            while ((command = parser.parseCommand()) != null) {
                System.out.println("[" + clientAddr + "] >> " + command);

                try {
                    router.dispatch(command, out);
                } catch (IOException writeErr) {
                    // If we can't write to the client, the connection is dead
                    System.err.println("[" + clientAddr + "] Write failed: "
                            + writeErr.getMessage());
                    break;
                }
            }

        } catch (IOException e) {
            // Connection reset, parse error, etc.
            System.err.println("[" + clientAddr + "] Connection error: "
                    + e.getMessage());
        } finally {
            closeQuietly();
            System.out.println("Connection closed: " + clientAddr);
        }
    }

    /**
     * Creates a {@link RespParser} from the client socket's input stream.
     * Extracted for clarity and potential future testing.
     */
    private RespParser buildParser() throws IOException {
        return new RespParser(clientSocket.getInputStream());
    }

    /**
     * Closes the client socket, swallowing any exception on close.
     */
    private void closeQuietly() {
        try {
            if (!clientSocket.isClosed()) {
                clientSocket.close();
            }
        } catch (IOException ignored) {
            // Nothing useful to do here
        }
    }
}
