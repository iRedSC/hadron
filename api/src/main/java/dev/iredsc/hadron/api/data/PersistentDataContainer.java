package dev.iredsc.hadron.api.data;

import java.util.Set;

/**
 * Typed values under {@link NamespacedKey}s, saved with whatever holds them, as in Bukkit. Get one with
 * {@link PersistentData#of}.
 *
 * <p>Every change takes effect at once where the data lives: on an item stack it is written into the stack, on an
 * entity, block or chunk it is saved with it. There is nothing to write back afterwards.
 *
 * <p>Values read back are copies, except a nested {@link PersistentDataType#TAG_CONTAINER}, which is live.
 */
public interface PersistentDataContainer {
    /** Stores the value, replacing whatever the key held, of any type. Null is refused: use {@link #remove}. */
    <P, C> void set(NamespacedKey key, PersistentDataType<P, C> type, C value);

    /** Whether the key holds a value stored as this type. */
    <P, C> boolean has(NamespacedKey key, PersistentDataType<P, C> type);

    /** Whether the key holds a value of any type. */
    boolean has(NamespacedKey key);

    /**
     * The value, or null when the key holds none.
     *
     * @throws IllegalArgumentException when the key holds a value of another type; the message names both
     */
    <P, C> C get(NamespacedKey key, PersistentDataType<P, C> type);

    /** The value, or {@code defaultValue} when the key holds none. A value of another type still throws. */
    <P, C> C getOrDefault(NamespacedKey key, PersistentDataType<P, C> type, C defaultValue);

    /** A copy of the keys that hold values, across every plugin's namespace. */
    Set<NamespacedKey> getKeys();

    /** Removes the key's value. Does nothing if it holds none. */
    void remove(NamespacedKey key);

    boolean isEmpty();

    /** Copies every value into {@code other}; with {@code replace} false, keys {@code other} already has are kept. */
    void copyTo(PersistentDataContainer other, boolean replace);

    PersistentDataAdapterContext getAdapterContext();
}
