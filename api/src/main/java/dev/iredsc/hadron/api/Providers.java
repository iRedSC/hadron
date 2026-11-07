package dev.iredsc.hadron.api;

import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.plugin.Server;

/** The plugin providing a Hadron service right now, looked up on every call so a reload is seen at once. */
final class Providers {
    private Providers() {}

    /** Null when no plugin provides it, or before Hadron has started. */
    static <T> @Nullable T get(Class<T> service) {
        Server server = Hadron.Lifecycle.running();
        return server == null ? null : server.getServicesManager().load(service);
    }
}
