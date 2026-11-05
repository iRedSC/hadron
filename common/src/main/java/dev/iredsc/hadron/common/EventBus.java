package dev.iredsc.hadron.common;

import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.EventPriority;
import dev.iredsc.hadron.api.event.Listener;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;

/**
 * Hadron's own events, the same on every loader. A handler for an event class hears that class and all its
 * subclasses, by priority, then in the order registered. Registering and removing happen on the server thread;
 * {@link #post} may be called from any thread, and runs each handler on the calling one.
 */
final class EventBus {
    /** One handler method, removable. */
    static final class Subscription {
        final Listener listener;
        final Class<?> type;
        private final EventPriority priority;
        private final boolean ignoreCancelled;
        private final long order;
        private final MethodHandle handle;
        private final String where;
        private final Logger log;
        private final AtomicInteger failures = new AtomicInteger();
        /** Admission and removal share this monitor; calls already in progress are tracked until they finish. */
        private boolean removed;
        private final Map<Thread, Integer> calls = new HashMap<>();
        private EventBus bus;

        private Subscription(Listener listener, Class<?> type, EventHandler h, long order, MethodHandle handle,
                String where, Logger log) {
            this.listener = listener;
            this.type = type;
            this.priority = h.priority();
            this.ignoreCancelled = h.ignoreCancelled();
            this.order = order;
            this.handle = handle;
            this.where = where;
            this.log = log;
        }

        void remove() {
            deactivate();
        }

        void deactivate() {
            synchronized (this) {
                if (removed) return;
                removed = true;
            }
            bus.remove(this);
        }

        boolean awaitIdle(long deadline) {
            boolean interrupted = false;
            boolean idle;
            synchronized (this) {
                while (calls.size() > (calls.containsKey(Thread.currentThread()) ? 1 : 0)) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) break;
                    try {
                        TimeUnit.NANOSECONDS.timedWait(this, remaining);
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
                idle = calls.size() == (calls.containsKey(Thread.currentThread()) ? 1 : 0);
            }
            if (interrupted) Thread.currentThread().interrupt();
            return idle;
        }

        private void call(Event event) {
            Thread thread = Thread.currentThread();
            synchronized (this) {
                if (removed) return;
                calls.merge(thread, 1, Integer::sum);
            }
            try {
                if (ignoreCancelled && event instanceof Cancellable c && c.isCancelled()) return;
                handle.invoke(event);
            } catch (Throwable t) {
                // A plugin's bug costs that plugin its event, not the server its tick. Logged in full a few times,
                // then counted, so a handler on a frequent event can't flood the log.
                int n = failures.incrementAndGet();
                if (n <= 5) {
                    log.error(where + " threw on " + event.getClass().getName(), t);
                } else if (n % 1000 == 0) {
                    log.error(where + " has thrown " + n + " times; latest failure", t);
                }
            } finally {
                synchronized (this) {
                    int remaining = calls.get(thread) - 1;
                    if (remaining == 0) calls.remove(thread);
                    else calls.put(thread, remaining);
                    notifyAll();
                }
            }
        }
    }

    private static final Comparator<Subscription> ORDER = Comparator
            .comparing((Subscription s) -> s.priority).thenComparingLong(s -> s.order);
    private static final Subscription[] NONE = new Subscription[0];

    /** By the event class each handler takes. Guarded by this. */
    private final Map<Class<?>, List<Subscription>> byType = new LinkedHashMap<>();
    /** Every handler that hears a concrete event class, sorted; rebuilt after any change. */
    private final Map<Class<?>, Subscription[]> baked = new HashMap<>();
    private final AtomicLong registered = new AtomicLong();

    /** Subscribes every {@code @EventHandler} method; all or nothing. */
    List<Subscription> subscribe(Listener listener, String plugin, Logger log) {
        List<Subscription> out = new ArrayList<>();
        for (Method m : methods(listener.getClass())) {
            EventHandler h = m.getAnnotation(EventHandler.class);
            if (h != null) out.add(make(listener, m, h, plugin, log));
        }
        synchronized (this) {
            for (Subscription s : out) {
                s.bus = this;
                byType.computeIfAbsent(s.type, k -> new ArrayList<>()).add(s);
            }
            baked.clear();
        }
        return out;
    }

    private synchronized void remove(Subscription s) {
        List<Subscription> list = byType.get(s.type);
        if (list != null && list.remove(s) && list.isEmpty()) byType.remove(s.type);
        baked.clear();
    }

    /** Runs every handler that hears the event. */
    void post(Event event) {
        Subscription[] handlers;
        synchronized (this) {
            handlers = baked.computeIfAbsent(event.getClass(), this::bake);
        }
        for (Subscription s : handlers) s.call(event);
    }

    private synchronized Subscription[] bake(Class<?> type) {
        List<Subscription> out = new ArrayList<>();
        for (Map.Entry<Class<?>, List<Subscription>> e : byType.entrySet()) {
            if (e.getKey().isAssignableFrom(type)) out.addAll(e.getValue());
        }
        if (out.isEmpty()) return NONE;
        out.sort(ORDER);
        return out.toArray(NONE);
    }

    /** Each signature once, the most derived: everything declared up the class chain, any visibility. */
    private static List<Method> methods(Class<?> type) {
        Map<String, Method> out = new LinkedHashMap<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.isBridge()) out.putIfAbsent(m.getName() + Arrays.toString(m.getParameterTypes()), m);
            }
        }
        return new ArrayList<>(out.values());
    }

    private Subscription make(Listener listener, Method m, EventHandler h, String plugin, Logger log) {
        String where = plugin + ": " + m.getDeclaringClass().getSimpleName() + "." + m.getName();
        Class<?>[] params = m.getParameterTypes();
        if (params.length != 1 || !Event.class.isAssignableFrom(params[0])) {
            throw new IllegalArgumentException(where + " has @EventHandler but doesn't take exactly one Hadron event "
                    + "(dev.iredsc.hadron.api.event)");
        }
        MethodHandle handle;
        try {
            m.setAccessible(true);
            MethodHandle raw = MethodHandles.lookup().unreflect(m);
            handle = Modifier.isStatic(m.getModifiers()) ? raw : raw.bindTo(listener);
        } catch (IllegalAccessException | RuntimeException e) {
            throw new IllegalArgumentException(where + " can't be called by Hadron: " + e.getMessage(), e);
        }
        return new Subscription(listener, params[0], h, registered.incrementAndGet(), handle, where, log);
    }
}
