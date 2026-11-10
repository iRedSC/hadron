package dev.iredsc.hadron.common;

import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.api.event.server.ServiceRegisterEvent;
import dev.iredsc.hadron.api.event.server.ServiceUnregisterEvent;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.api.plugin.ServicePriority;
import dev.iredsc.hadron.api.plugin.ServicesManager;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Registered providers, and the register and unregister events that announce them. Each change queues its events
 * under the registry lock, in the order the changes happen; they are delivered outside it, so a listener can look
 * services up, or change them, without deadlocking or being heard out of order. One thread delivers at a time: a
 * change made while another thread (or a listener) is delivering is announced by that delivery, after what came first.
 */
final class Services implements ServicesManager {
    private final PluginHost host;
    private final Map<Class<?>, List<Registration>> byService = new HashMap<Class<?>, List<Registration>>();
    /** Events waiting to be delivered, oldest first. Guarded by this. */
    private final ArrayDeque<Event> outbox = new ArrayDeque<Event>();
    /** Whether some thread is delivering the outbox. Guarded by this. */
    private boolean delivering;

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
        deliver();
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
        outbox.add(new ServiceRegisterEvent(service, provider, plugin));
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
        removeWhere(provider, null);
        deliver();
    }

    @Override
    public void unregisterAll(JavaPlugin plugin) {
        removeWhere(null, plugin);
        deliver();
    }

    /** Posts queued events in order, outside the lock, unless a delivery already running will reach them. */
    private void deliver() {
        synchronized (this) {
            if (delivering) return;
            delivering = true;
        }
        boolean drained = false;
        try {
            while (true) {
                Event next;
                synchronized (this) {
                    next = outbox.poll();
                    if (next == null) {
                        delivering = false;
                        drained = true;
                        return;
                    }
                }
                host.post(next);
            }
        } finally {
            if (!drained) {
                synchronized (this) {
                    delivering = false;
                }
            }
        }
    }

    /** Also drops emptied services, whose interface may be a plugin's class that would keep its old loader alive. */
    private synchronized void removeWhere(@Nullable Object provider, @Nullable JavaPlugin plugin) {
        for (Iterator<Map.Entry<Class<?>, List<Registration>>> lists = byService.entrySet().iterator(); lists.hasNext(); ) {
            Map.Entry<Class<?>, List<Registration>> entry = lists.next();
            List<Registration> list = entry.getValue();
            for (Iterator<Registration> it = list.iterator(); it.hasNext(); ) {
                Registration r = it.next();
                if (r.provider == provider || r.plugin == plugin) {
                    it.remove();
                    outbox.add(unregistered(entry.getKey(), r.provider, r.plugin));
                }
            }
            if (list.isEmpty()) lists.remove();
        }
    }

    /** The service's own type, checked again here: {@link #add} only lets in providers that implement it. */
    private static <T> ServiceUnregisterEvent unregistered(Class<T> service, Object provider, JavaPlugin plugin) {
        return new ServiceUnregisterEvent(service, service.cast(provider), plugin);
    }

    @Override
    public synchronized <T> @Nullable T load(Class<T> service) {
        // Vault habit: plugins look the economy up as a service. It is Hadron's own, whoever stores the accounts.
        if (service == dev.iredsc.hadron.api.economy.Economy.class) {
            return service.cast(dev.iredsc.hadron.api.economy.Economy.Lifecycle.running());
        }
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
