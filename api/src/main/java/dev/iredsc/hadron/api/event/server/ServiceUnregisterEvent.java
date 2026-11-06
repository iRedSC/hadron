package dev.iredsc.hadron.api.event.server;

import dev.iredsc.hadron.api.plugin.JavaPlugin;

/**
 * A provider was unregistered from a service, by its plugin or because its plugin disabled. Not
 * cancellable; check {@code getServicesManager().load(service)} for the provider now in use.
 */
public class ServiceUnregisterEvent extends ServerEvent {
    private final Class<?> service;
    private final Object provider;
    private final JavaPlugin plugin;

    public ServiceUnregisterEvent(Class<?> service, Object provider, JavaPlugin plugin) {
        this.service = service;
        this.provider = provider;
        this.plugin = plugin;
    }

    /** The service interface, such as an economy API. */
    public Class<?> getService() {
        return service;
    }

    public Object getProvider() {
        return provider;
    }

    /** The plugin that registered the provider. */
    public JavaPlugin getPlugin() {
        return plugin;
    }
}
