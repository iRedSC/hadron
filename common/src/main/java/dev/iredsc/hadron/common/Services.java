package dev.iredsc.hadron.common;

import dev.iredsc.hadron.api.event.server.ServiceRegisterEvent;
import dev.iredsc.hadron.api.event.server.ServiceUnregisterEvent;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.api.plugin.ServicePriority;
import dev.iredsc.hadron.api.plugin.ServicesManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

final class Services implements ServicesManager {
    private final PluginHost host;
    private final Map<Class<?>, List<Registration>> byService = new HashMap<Class<?>, List<Registration>>();

    Services(PluginHost host) {
        this.host = host;
    }

    private static final class Registration {
        final Object provider;
        final JavaPlugin plugin;
        final ServicePriority priority;

        Registration(Object provider, JavaPlugin plugin, ServicePriority priority) {
            this.provider = provider;
            this.plugin = plugin;
            this.priority = priority;
        }
    }

    @Override
    public <T> void register(Class<T> service, T provider, JavaPlugin plugin, ServicePriority priority) {
        add(service, provider, plugin, priority);
        // Outside the lock, so a listener that looks services up can't deadlock against another thread.
        host.post(new ServiceRegisterEvent(service, provider, plugin));
    }

    private synchronized <T> void add(Class<T> service, T provider, JavaPlugin plugin, ServicePriority priority) {
        if (!service.isInstance(provider)) {
            throw new IllegalArgumentException(provider + " doesn't implement " + service.getName());
        }
        // Checked under the lock unregisterAll takes, which runs only after the plugin stops being alive.
        if (!host.isAlive(plugin)) throw new IllegalStateException(plugin.getName() + " can't register services while unloading");
        List<Registration> list = byService.get(service);
        if (list == null) byService.put(service, list = new ArrayList<Registration>());
        list.add(new Registration(provider, plugin, priority));
        // Stable sort: among equal priorities the first registered stays first.
        Collections.sort(list, new Comparator<Registration>() {
            @Override
            public int compare(Registration a, Registration b) {
                return b.priority.compareTo(a.priority);
            }
        });
    }

    @Override
    public void unregister(Object provider) {
        announce(removeWhere(provider, null));
    }

    @Override
    public void unregisterAll(JavaPlugin plugin) {
        announce(removeWhere(null, plugin));
    }

    /** Outside the lock, so a listener that looks services up can't deadlock against another thread. */
    private void announce(List<ServiceUnregisterEvent> removed) {
        for (ServiceUnregisterEvent event : removed) host.post(event);
    }

    /** Also drops emptied services, whose interface may be a plugin's class that would keep its old loader alive. */
    private synchronized List<ServiceUnregisterEvent> removeWhere(Object provider, JavaPlugin plugin) {
        List<ServiceUnregisterEvent> removed = new ArrayList<ServiceUnregisterEvent>();
        for (Iterator<Map.Entry<Class<?>, List<Registration>>> lists = byService.entrySet().iterator(); lists.hasNext(); ) {
            Map.Entry<Class<?>, List<Registration>> entry = lists.next();
            List<Registration> list = entry.getValue();
            for (Iterator<Registration> it = list.iterator(); it.hasNext(); ) {
                Registration r = it.next();
                if (r.provider == provider || r.plugin == plugin) {
                    it.remove();
                    removed.add(new ServiceUnregisterEvent(entry.getKey(), r.provider, r.plugin));
                }
            }
            if (list.isEmpty()) lists.remove();
        }
        return removed;
    }

    @Override
    public synchronized <T> T load(Class<T> service) {
        List<Registration> list = byService.get(service);
        return list == null || list.isEmpty() ? null : service.cast(list.get(0).provider);
    }

    @Override
    public synchronized <T> List<T> loadAll(Class<T> service) {
        List<T> out = new ArrayList<T>();
        List<Registration> list = byService.get(service);
        if (list != null) for (Registration r : list) out.add(service.cast(r.provider));
        return out;
    }

    @Override
    public synchronized boolean isProvidedFor(Class<?> service) {
        List<Registration> list = byService.get(service);
        return list != null && !list.isEmpty();
    }
}
