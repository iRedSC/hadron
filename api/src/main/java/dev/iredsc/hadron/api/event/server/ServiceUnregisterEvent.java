package dev.iredsc.hadron.api.event.server;

import dev.iredsc.hadron.api.event.FiredBy;
import dev.iredsc.hadron.api.plugin.JavaPlugin;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A provider was unregistered from a service, by its plugin or because its plugin disabled. Not
 * cancellable; check {@code getServicesManager().load(service)} for the provider now in use.
 */
@FiredBy(core = FULL)
public class ServiceUnregisterEvent extends ServiceEvent {
    public <T> ServiceUnregisterEvent(Class<T> service, T provider, JavaPlugin plugin) {
        super(service, provider, plugin);
    }
}
