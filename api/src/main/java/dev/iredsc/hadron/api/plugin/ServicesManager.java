package dev.iredsc.hadron.api.plugin;

import java.util.List;

/**
 * Lets plugins provide implementations of an interface for others to use, like Bukkit's (and Vault's) services.
 * The interface has to be visible to both sides: declare it in Hadron's API, or in the providing plugin, which the
 * user then lists under {@code depend} or {@code softdepend} in plugin.yml. A provider is unregistered when its
 * plugin disables, and every plugin depending on it is reloaded with it, so nobody keeps calling the old one.
 * Register in onLoad, so every plugin can use the service in its own onEnable whatever order plugins load in.
 */
public interface ServicesManager {
    <T> void register(Class<T> service, T provider, JavaPlugin plugin, ServicePriority priority);

    void unregister(Object provider);

    void unregisterAll(JavaPlugin plugin);

    /** The highest priority provider, or null when nobody provides it. */
    <T> T load(Class<T> service);

    /** Every provider, highest priority first. */
    <T> List<T> loadAll(Class<T> service);

    boolean isProvidedFor(Class<?> service);
}
