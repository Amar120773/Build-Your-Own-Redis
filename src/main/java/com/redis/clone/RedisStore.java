package com.redis.clone;

import java.util.concurrent.ConcurrentHashMap;

/**
 * The global, thread-safe key-value store for the Redis clone.
 *
 * <h3>Concurrency Model</h3>
 *
 * <p>The store is backed by a {@link ConcurrentHashMap} which provides:
 * <ul>
 *   <li><b>Lock-free reads</b> — {@code get()} never blocks, even under
 *       heavy concurrent writes.</li>
 *   <li><b>Fine-grained locking on writes</b> — only the hash bucket
 *       being modified is locked, so concurrent writes to different keys
 *       proceed in parallel.</li>
 *   <li><b>Safe publication</b> — a value written by thread A is
 *       guaranteed to be visible to thread B on a subsequent {@code get()},
 *       thanks to the volatile semantics inside {@code ConcurrentHashMap}.</li>
 * </ul>
 *
 * <p>Because {@link StoreEntry} is immutable, there is no risk of one
 * thread reading a partially-constructed entry written by another thread.
 *
 * <h3>Expiration Strategy — Lazy Deletion</h3>
 *
 * <p>Expired keys are not removed proactively by a background thread.
 * Instead, each {@link #get(String)} call checks the entry's expiration
 * timestamp and deletes it on the spot if it has expired.  This is the
 * same "lazy expiration" strategy used by real Redis for its passive
 * expiry path.
 *
 * <p>The trade-off is that expired-but-unrequested keys linger in memory
 * until they are eventually accessed (or overwritten by a new {@code SET}).
 * A future phase can add an active-expiry background sweep if needed.
 *
 * <h3>Singleton Access</h3>
 *
 * <p>Use {@link #getInstance()} to obtain the shared store.  The singleton
 * is initialized via the "initialization-on-demand holder" idiom, which is
 * both lazy and thread-safe without synchronization.
 */
public class RedisStore {

    // ---------------------------------------------------------------
    // Singleton (initialization-on-demand holder)
    // ---------------------------------------------------------------

    private RedisStore() {
        // private — use getInstance()
    }

    private static class Holder {
        static final RedisStore INSTANCE = new RedisStore();
    }

    /**
     * Returns the shared {@code RedisStore} singleton.
     */
    public static RedisStore getInstance() {
        return Holder.INSTANCE;
    }

    // ---------------------------------------------------------------
    // Storage
    // ---------------------------------------------------------------

    private final ConcurrentHashMap<String, StoreEntry> map = new ConcurrentHashMap<>();

    /**
     * Stores a key-value pair with no expiration.
     *
     * @param key   the key
     * @param value the value
     */
    public void set(String key, String value) {
        map.put(key, new StoreEntry(value));
    }

    /**
     * Stores a key-value pair with a millisecond-precision TTL.
     *
     * <p>The TTL is converted to an <b>absolute</b> epoch-millis timestamp
     * at write time.  This avoids drift: if the GET arrives 50 ms later
     * than expected, it still compares against the same fixed deadline.
     *
     * @param key      the key
     * @param value    the value
     * @param ttlMs    time-to-live in milliseconds (must be &gt; 0)
     */
    public void set(String key, String value, long ttlMs) {
        long expiresAt = System.currentTimeMillis() + ttlMs;
        map.put(key, new StoreEntry(value, expiresAt));
    }

    /**
     * Retrieves the value for {@code key}, performing lazy expiration.
     *
     * <ol>
     *   <li>Look up the entry in the map.</li>
     *   <li>If absent → return {@code null}.</li>
     *   <li>If present and expired → remove it and return {@code null}.</li>
     *   <li>Otherwise → return the value.</li>
     * </ol>
     *
     * <p><b>Race condition note:</b>  Two threads could both see an expired
     * entry and both call {@code remove()}.  This is harmless — the second
     * {@code remove()} is a no-op, and neither thread returns stale data.
     *
     * @param key the key to look up
     * @return the value, or {@code null} if the key is missing or expired
     */
    public String get(String key) {
        StoreEntry entry = map.get(key);
        if (entry == null) {
            return null;
        }

        if (entry.isExpired()) {
            // Lazy deletion — clean up the expired key
            map.remove(key);
            return null;
        }

        return entry.getValue();
    }

    /**
     * Deletes a key from the store.
     *
     * @param key the key to delete
     * @return {@code true} if the key existed (and was removed)
     */
    public boolean delete(String key) {
        return map.remove(key) != null;
    }

    /**
     * Returns the number of keys currently in the store (including any
     * that may be logically expired but not yet lazily removed).
     */
    public int size() {
        return map.size();
    }

    /**
     * Returns {@code true} if the key exists <b>and</b> has not expired.
     */
    public boolean exists(String key) {
        return get(key) != null;
    }

    /**
     * Stores a key-value pair with an <b>absolute</b> expiration
     * timestamp (epoch-millis).  Used by {@link RdbFileReader} to load
     * entries that were serialized with a pre-computed deadline.
     *
     * @param key             the key
     * @param value           the value
     * @param expiresAtMillis absolute epoch-millis, or
     *                        {@link StoreEntry#NO_EXPIRY} for no expiry
     */
    public void setAbsolute(String key, String value, long expiresAtMillis) {
        map.put(key, new StoreEntry(value, expiresAtMillis));
    }

    /**
     * Returns all keys that match the given pattern and are not expired.
     *
     * <p>Currently only supports {@code *} (match all).
     */
    public java.util.Set<String> getKeys(String pattern) {
        java.util.Set<String> result = java.util.concurrent.ConcurrentHashMap.newKeySet();
        for (String key : map.keySet()) {
            // Check expiry via get() — also cleans up expired keys
            if (get(key) != null) {
                if ("*".equals(pattern) || key.equals(pattern)) {
                    result.add(key);
                }
            }
        }
        return result;
    }
}
