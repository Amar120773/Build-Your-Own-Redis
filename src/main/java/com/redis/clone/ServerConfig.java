package com.redis.clone;

import java.util.HashMap;
import java.util.Map;

/**
 * Holds server-wide configuration parsed from command-line arguments.
 *
 * <p>Supported arguments:
 * <ul>
 *   <li>{@code --port <n>}         — TCP listen port (default: 6379)</li>
 *   <li>{@code --dir <path>}       — directory containing the RDB file</li>
 *   <li>{@code --dbfilename <name>} — name of the RDB file</li>
 * </ul>
 *
 * <p>Singleton, accessible from any class via {@link #getInstance()}.
 */
public class ServerConfig {

    private static final ServerConfig INSTANCE = new ServerConfig();

    private int port = 6379;
    private String dir = null;
    private String dbFilename = null;

    private ServerConfig() {}

    public static ServerConfig getInstance() {
        return INSTANCE;
    }

    // ---------------------------------------------------------------
    // CLI parsing
    // ---------------------------------------------------------------

    /**
     * Parses the raw {@code args} array from {@code main()}.
     *
     * <p>Arguments are expected in {@code --key value} pairs.
     * Unknown keys are silently ignored.
     */
    public void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i].toLowerCase()) {
                case "--port" -> {
                    if (i + 1 < args.length) {
                        try {
                            port = Integer.parseInt(args[++i]);
                        } catch (NumberFormatException e) {
                            System.err.println("Invalid port: " + args[i]);
                        }
                    }
                }
                case "--dir" -> {
                    if (i + 1 < args.length) dir = args[++i];
                }
                case "--dbfilename" -> {
                    if (i + 1 < args.length) dbFilename = args[++i];
                }
                default -> {
                    // skip unknown flags and their values
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------------

    public int getPort() { return port; }
    public String getDir() { return dir; }
    public String getDbFilename() { return dbFilename; }

    /**
     * Returns the full path to the RDB file, or {@code null} if either
     * {@code --dir} or {@code --dbfilename} was not provided.
     */
    public String getRdbPath() {
        if (dir == null || dbFilename == null) return null;
        // Use File.separator for cross-platform compatibility
        return dir + java.io.File.separator + dbFilename;
    }

    /**
     * Returns a config value by key name (case-insensitive), for
     * the {@code CONFIG GET} command.
     */
    public Map<String, String> getConfigValues(String pattern) {
        Map<String, String> result = new HashMap<>();
        String p = pattern.toLowerCase();

        if (matches(p, "dir") && dir != null) {
            result.put("dir", dir);
        }
        if (matches(p, "dbfilename") && dbFilename != null) {
            result.put("dbfilename", dbFilename);
        }

        return result;
    }

    /**
     * Simple glob matching — supports only {@code *} (match all).
     */
    private boolean matches(String pattern, String key) {
        return pattern.equals("*") || pattern.equals(key);
    }
}
