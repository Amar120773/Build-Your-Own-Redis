# What is Redis!

Redis is a super-fast, temporary notebook for your computer applications.

To understand it, let’s compare it to a normal database (like SQL or Postgres), which is like a filing cabinet. If you want to read a file from a filing cabinet, you have to stand up, walk over, pull open the heavy drawer, find the folder, and pull out the paper. This takes time. This is how normal databases work—they save data permanently to a hard drive, which is safe, but relatively slow to read from.

Redis, on the other hand, is like a sticky note on your desk. If you want to read the sticky note, you just look down. It’s instant. Redis achieves this speed by storing all its data in your computer's RAM (Memory) instead of the hard drive.

Because reading from memory is incredibly fast, Redis is heavily used for things where speed is everything:

Caching: Storing the results of a really slow database query so the next person who asks for it gets the answer instantly.
Session Storage: Keeping track of users who are currently logged into a website.
Shopping Carts: Remembering what you put in your cart while you browse around Amazon.
Temporary Data: Like you just built! When you told Redis to SET temp_key "I will vanish" PX 5000, it held onto it for exactly 5 seconds and then threw it away.
The trade-off is that RAM is expensive and temporary. If your computer loses power, the sticky note gets thrown away (though Redis has clever backup features, like the .rdb files you wrote a parser for, to save snapshots of the sticky notes to the filing cabinet just in case!).

So when you built your clone using a ConcurrentHashMap in Java, you literally built the exact thing Redis is famous for: a blazing-fast, memory-based dictionary!


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


## Demo Output

Here is a real interaction with the server using the official `redis-cli`:

$ redis-cli PING
PONG

$ redis-cli SET message "Hello from my own Redis server!"
OK

$ redis-cli GET message
"Hello from my own Redis server!"

$ redis-cli SET temp_key "I will vanish in 5 seconds" PX 5000
OK

$ redis-cli SET name "John Doe"
OK

$ redis-cli GET name
"John Doe"

$ redis-cli SET disappearing_message "I'll be gone soon" PX 5000
OK

# Wait 5 seconds...
$ redis-cli GET disappearing_message
(nil)
