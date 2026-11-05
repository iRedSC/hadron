package dev.iredsc.hadron.core;

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
    public synchronized <T> void register(Class<T> service, T provider, JavaPlugin plugin, ServicePriority priority) {
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
    public synchronized void unregister(Object provider) {
        removeWhere(provider, null);
    }

    @Override
    public synchronized void unregisterAll(JavaPlugin plugin) {
        removeWhere(null, plugin);
    }

    /** Also drops emptied services, whose interface may be a plugin's class that would keep its old loader alive. */
    private void removeWhere(Object provider, JavaPlugin plugin) {
        for (Iterator<List<Registration>> lists = byService.values().iterator(); lists.hasNext(); ) {
            List<Registration> list = lists.next();
            for (Iterator<Registration> it = list.iterator(); it.hasNext(); ) {
                Registration r = it.next();
                if (r.provider == provider || r.plugin == plugin) it.remove();
            }
            if (list.isEmpty()) lists.remove();
        }
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
