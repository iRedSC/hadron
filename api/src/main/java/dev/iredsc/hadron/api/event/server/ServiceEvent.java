package dev.iredsc.hadron.api.event.server;

import dev.iredsc.hadron.api.plugin.JavaPlugin;
import java.util.Objects;

/**
 * A provider was registered for, or unregistered from, a service. The provider always implements the service: the
 * constructors take a {@code Class<T>} and a {@code T}, so the two can't be mismatched.
 */
public abstract class ServiceEvent extends ServerEvent {
    private final Class<?> service;
    private final Object provider;
    private final JavaPlugin plugin;

    protected <T> ServiceEvent(Class<T> service, T provider, JavaPlugin plugin) {
        this.service = Objects.requireNonNull(service, "service; pass the service interface, such as Economy.class");
        this.provider = Objects.requireNonNull(provider, "provider; pass the object that implements the service");
        this.plugin = Objects.requireNonNull(plugin, "plugin; pass the plugin that registered the provider");
        // A raw Class or an unchecked cast can still slip a mismatch past the compiler; refuse it here.
        if (!service.isInstance(provider)) {
            throw new IllegalArgumentException(provider.getClass().getName() + " doesn't implement "
                    + service.getName() + ": pass the provider registered for that service, or its own service class");
        }
    }

    /** The service interface, such as an economy API. */
    public Class<?> getService() {
        return service;
    }

    /** The provider, an instance of {@link #getService()}. */
    public Object getProvider() {
        return provider;
    }

    /** The plugin that registered the provider. */
    public JavaPlugin getPlugin() {
        return plugin;
    }
}
