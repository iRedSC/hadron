package dev.iredsc.hadron.api.event.server;

import dev.iredsc.hadron.api.event.FiredBy;
import dev.iredsc.hadron.api.plugin.JavaPlugin;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A provider was registered for a service, such as an economy or permissions provider. Not
 * cancellable; check {@code getServicesManager().load(service)} for the provider now in use.
 * Service events arrive in the order the changes happened, but not always on the thread that made them: a change made
 * while another thread is announcing earlier ones is announced by that thread, so {@code register} can return before
 * its own event has been heard.
 */
@FiredBy(core = FULL)
public class ServiceRegisterEvent extends ServiceEvent {
    public <T> ServiceRegisterEvent(Class<T> service, T provider, JavaPlugin plugin) {
        super(service, provider, plugin);
    }
}
