package dev.iredsc.hadron.api.data;

/** Handed to a custom {@link PersistentDataType}, as in Bukkit, so it can build nested containers. */
public interface PersistentDataAdapterContext {
    /** A new, empty container that belongs to nothing until it is stored with {@link PersistentDataType#TAG_CONTAINER}. */
    PersistentDataContainer newPersistentDataContainer();
}
