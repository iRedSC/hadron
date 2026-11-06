package dev.iredsc.hadron.api.event.server;

import dev.iredsc.hadron.api.plugin.JavaPlugin;

/**
 * A provider was registered for a service, such as an economy or permissions provider. Not
 * cancellable; check {@code getServicesManager().load(service)} for the provider now in use.
 */
public class ServiceRegisterEvent extends ServerEvent {
    private final Class<?> service;
    private final Object provider;
    private final JavaPlugin plugin;

    public ServiceRegisterEvent(Class<?> service, Object provider, JavaPlugin plugin) {
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
