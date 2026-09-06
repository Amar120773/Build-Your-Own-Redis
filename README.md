# Build Your Own Redis (Java)

A Redis server clone built with standard Java networking libraries (`java.net` + `java.io`). No external dependencies.

## Features

- **RESP Protocol Parser** — full v2 support (Simple Strings, Errors, Integers, Bulk Strings, Arrays)
- **Commands:** `PING`, `ECHO`, `SET` (with `PX` expiry), `GET`, `CONFIG GET`, `KEYS`
- **Thread-safe storage** — `ConcurrentHashMap` with lazy expiration
- **RDB file loading** — parses binary Redis snapshots on startup

## Quick Start (GitHub Codespaces)

1. Click **Code → Codespaces → Create codespace on main**
2. Wait for the container to build (~1 min)
3. In the terminal:

```bash
# Start the server
mvn compile exec:java

# In a second terminal (split terminal with Ctrl+Shift+5):
redis-cli PING          # → PONG
redis-cli SET foo bar   # → OK
redis-cli GET foo       # → "bar"
```

## Quick Start (Local)

Requires **Java 17+** and **Maven 3.8+**.

```bash
mvn compile exec:java
```

## Project Structure

```
src/main/java/com/redis/clone/
├── Main.java           — TCP server, CLI parsing, RDB loading
├── ClientHandler.java  — Per-client connection handler (thread-per-client)
├── RespParser.java     — Streaming RESP v2 protocol deserializer
├── CommandRouter.java  — Command dispatch + RESP response encoders
├── RedisStore.java     — ConcurrentHashMap-backed singleton store
├── StoreEntry.java     — Immutable {value, expiresAtMillis} entry
├── ServerConfig.java   — CLI argument parser (--port, --dir, --dbfilename)
└── RdbFileReader.java  — Binary RDB file parser with length encoding
```

## CLI Arguments

```bash
mvn compile exec:java -Dexec.args="--port 6380 --dir /tmp --dbfilename dump.rdb"
```

| Argument       | Default | Description                    |
|----------------|---------|--------------------------------|
| `--port`       | 6379    | TCP listen port                |
| `--dir`        | (none)  | Directory containing RDB file |
| `--dbfilename` | (none)  | RDB snapshot filename          |
