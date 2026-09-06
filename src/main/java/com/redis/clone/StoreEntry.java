package com.redis.clone;

/**
 * Represents a single value stored in the Redis data store.
 *
 * <p>Each entry holds the string value and an optional absolute expiration
 * timestamp in milliseconds since the epoch.  A value of {@code -1} for
 * {@link #expiresAtMillis} means the key never expires.
 *
 * <p><b>Immutability:</b> Instances are effectively immutable once
 * constructed — there are no setters.  This is intentional: when a key
 * is updated, a <em>new</em> {@code StoreEntry} replaces the old one
 * inside the {@link java.util.concurrent.ConcurrentHashMap}, which
 * guarantees safe publication to all threads without additional locking.
 */
public class StoreEntry {

    /** Sentinel value indicating "no expiration". */
    public static final long NO_EXPIRY = -1L;

    private final String value;
    private final long expiresAtMillis;

    /**
     * Creates an entry that never expires.
     *
     * @param value the stored value
     */
    public StoreEntry(String value) {
        this(value, NO_EXPIRY);
    }

    /**
     * Creates an entry with an optional absolute expiration time.
     *
     * @param value           the stored value
     * @param expiresAtMillis absolute epoch-millis when the key expires,
     *                        or {@link #NO_EXPIRY} ({@code -1}) for no expiry
     */
    public StoreEntry(String value, long expiresAtMillis) {
        this.value = value;
        this.expiresAtMillis = expiresAtMillis;
    }

    /**
     * @return the stored value (never {@code null})
     */
    public String getValue() {
        return value;
    }

    /**
     * @return the absolute epoch-millis expiration time, or {@link #NO_EXPIRY}
     */
    public long getExpiresAtMillis() {
        return expiresAtMillis;
    }

    /**
     * Returns {@code true} if this entry has an expiration set <b>and</b>
     * the current wall-clock time has passed that expiration.
     */
    public boolean isExpired() {
        return expiresAtMillis != NO_EXPIRY
                && System.currentTimeMillis() > expiresAtMillis;
    }

    @Override
    public String toString() {
        return "StoreEntry{value='" + value + '\''
                + ", expiresAtMillis=" + expiresAtMillis + '}';
    }
}
