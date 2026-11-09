package dev.iredsc.hadron.common;

import dev.iredsc.hadron.checks.Nondeterministic;
import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hadron's own events, the same on every loader. A handler for an event class hears that class and all its
 * subclasses, by priority, then in the order registered. Registering and removing happen on the server thread;
 * {@link #post} may be called from any thread, and runs each handler on the calling one.
 */
final class EventBus {
    private static final Logger OBSERVER_LOG = LoggerFactory.getLogger("Hadron");
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
        /** Who the handler acts as when the event came with nobody behind it: its plugin. */
        private final Actor.Server owner;
        private final AtomicInteger failures = new AtomicInteger();
        /** Admission and removal share this monitor; calls already in progress are tracked until they finish. */
        private boolean removed;
        private final Map<Thread, Integer> calls = new HashMap<>();
        private @Nullable EventBus bus;

        private Subscription(Listener listener, Class<?> type, EventHandler h, long order, MethodHandle handle,
                String where, Logger log, String plugin) {
            this.listener = listener;
            this.type = type;
            this.priority = h.priority();
            this.ignoreCancelled = h.ignoreCancelled();
            this.order = order;
            this.handle = handle;
            this.where = where;
            this.log = log;
            this.owner = new Actor.Server("plugin " + plugin);
        }

        void remove() {
            deactivate();
        }

        void deactivate() {
            synchronized (this) {
                if (removed) return;
                removed = true;
            }
            EventBus owner = bus;
            if (owner != null) owner.remove(this);
        }

        @Nondeterministic(reason = "waits for handlers still running on other threads, up to the disable grace period")
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
            // A handler's own changes are its plugin's when nobody else is behind the event, as its tasks are.
            long scope = Actors.isEmpty() ? Actors.Port.enter(owner) : 0;
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
                if (scope != 0) Actors.Port.exit(scope);
                synchronized (this) {
                    int remaining = calls.getOrDefault(thread, 1) - 1;
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
    private static final Observer[] NO_OBSERVERS = new Observer[0];
    private static final Baked EMPTY = new Baked(NONE, NO_OBSERVERS);

    /**
     * Watches events after every plugin handled them, without being a plugin listener: what {@code /hadron trace}
     * uses. Never sees an event it doesn't {@link #hears}, and costs nothing while none is added.
     */
    interface Observer {
        /** Asked once per event class each time the bus rebakes; true makes {@link #listens} true for it too. */
        boolean hears(Class<?> type);

        /** Before any handler runs. Whatever it returns comes back to {@link #after}; null skips this event. */
        @Nullable Object before(Event event);

        /**
         * After every handler. {@code canceller} names the last handler that cancelled the event ("plugin:
         * Class.method"), or is null if no handler did.
         */
        void after(Event event, Object state, @Nullable String canceller);
    }

    /** Every handler and observer that hears one concrete event class. */
    private record Baked(Subscription[] handlers, Observer[] observers) {
        boolean any() {
            return handlers.length > 0 || observers.length > 0;
        }
    }

    /** By the event class each handler takes. Guarded by this. */
    private final Map<Class<?>, List<Subscription>> byType = new LinkedHashMap<>();
    /**
     * Every handler that hears a concrete event class, sorted. Replaced whole after any change, so reads never lock:
     * a bake racing a change lands in the map being thrown away.
     */
    private volatile Map<Class<?>, Baked> baked = new ConcurrentHashMap<>();
    private final AtomicLong registered = new AtomicLong();
    /** Guarded by this; replaced whole. */
    private Observer[] observers = NO_OBSERVERS;
    /**
     * Every event class the bus has been asked about, for {@code /hadron trace} to suggest and resolve names. A plugin's
     * classes leave with the plugin ({@link #forget}).
     */
    private final Set<Class<?>> seen = ConcurrentHashMap.newKeySet();

    /** Adds an observer; the events it hears are built and posted from now on, even with no plugin listening. */
    synchronized void observe(Observer observer) {
        Observer[] next = Arrays.copyOf(observers, observers.length + 1);
        next[observers.length] = observer;
        observers = next;
        baked = new ConcurrentHashMap<>();
    }

    synchronized void unobserve(Observer observer) {
        List<Observer> next = new ArrayList<>(Arrays.asList(observers));
        if (!next.remove(observer)) return;
        observers = next.isEmpty() ? NO_OBSERVERS : next.toArray(NO_OBSERVERS);
        baked = new ConcurrentHashMap<>();
    }

    /** Every event class asked about or posted since the server started. */
    Set<Class<?>> seen() {
        return Collections.unmodifiableSet(seen);
    }

    /**
     * Drops every event class an unloaded plugin version defined, so the bus doesn't keep its class loader alive: the
     * host calls this as each version is disposed. A class posted again later is simply seen again.
     */
    synchronized void forget(ClassLoader loader) {
        seen.removeIf(c -> c.getClassLoader() == loader);
        baked = new ConcurrentHashMap<>();
    }

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
            baked = new ConcurrentHashMap<>();
        }
        return out;
    }

    private synchronized void remove(Subscription s) {
        List<Subscription> list = byType.get(s.type);
        if (list != null && list.remove(s) && list.isEmpty()) byType.remove(s.type);
        baked = new ConcurrentHashMap<>();
    }

    /** Runs every handler that hears the event. */
    void post(Event event) {
        Baked b = handlers(event.getClass());
        if (b.observers.length == 0) {
            for (Subscription s : b.handlers) s.call(event);
            return;
        }
        observed(event, b);
    }

    /** {@link #post} while an observer hears this class: the same handler calls, watched. */
    private static void observed(Event event, Baked b) {
        Object[] states = new Object[b.observers.length];
        boolean anyone = false;
        for (int i = 0; i < states.length; i++) {
            Observer o = b.observers[i];
            states[i] = guard(() -> o.before(event));
            anyone |= states[i] != null;
        }
        Cancellable c = anyone && event instanceof Cancellable x ? x : null;
        String canceller = null;
        for (Subscription s : b.handlers) {
            boolean was = c != null && c.isCancelled();
            s.call(event);
            if (c != null && !was && c.isCancelled()) canceller = s.where;
            else if (c != null && was && !c.isCancelled()) canceller = null;
        }
        for (int i = 0; i < states.length; i++) {
            if (states[i] == null) continue;
            Observer o = b.observers[i];
            Object state = states[i];
            String by = canceller;
            guard(() -> {
                o.after(event, state, by);
                return null;
            });
        }
    }

    /**
     * Whether anything hears this event class. Cheap after the first call, so a chokepoint that runs millions of
     * times asks this before building the event.
     */
    boolean listens(Class<?> type) {
        return handlers(type).any();
    }

    private Baked handlers(Class<?> type) {
        Map<Class<?>, Baked> current = baked;
        Baked found = current.get(type);
        if (found == null) {
            found = bake(type);
            current.putIfAbsent(type, found);
        }
        return found;
    }

    private synchronized Baked bake(Class<?> type) {
        seen.add(type);
        List<Subscription> out = new ArrayList<>();
        for (Map.Entry<Class<?>, List<Subscription>> e : byType.entrySet()) {
            if (e.getKey().isAssignableFrom(type)) out.addAll(e.getValue());
        }
        List<Observer> watching = new ArrayList<>();
        for (Observer o : observers) if (Boolean.TRUE.equals(guard(() -> o.hears(type)))) watching.add(o);
        if (out.isEmpty() && watching.isEmpty()) return EMPTY;
        out.sort(ORDER);
        return new Baked(out.toArray(NONE), watching.isEmpty() ? NO_OBSERVERS : watching.toArray(NO_OBSERVERS));
    }

    /** An observer's bug never costs plugins their event. */
    private static <T> @Nullable T guard(java.util.function.Supplier<T> run) {
        try {
            return run.get();
        } catch (RuntimeException e) {
            OBSERVER_LOG.error("An event observer (/hadron trace) threw; the event went on without it", e);
            return null;
        }
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
        return new Subscription(listener, params[0], h, registered.incrementAndGet(), handle, where, log, plugin);
    }
}
