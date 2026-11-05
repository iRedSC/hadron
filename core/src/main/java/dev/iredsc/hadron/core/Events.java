package dev.iredsc.hadron.core;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.IModBusEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Puts plugin listeners on NeoForge's game bus, one {@code addListener} per {@code @SubscribeEvent} method, so
 * priority, {@code receiveCanceled} and event subclasses behave exactly as for mods, and each can be taken off again.
 */
final class Events {
    private Events() {}

    static IEventBus bus() {
        return NeoForge.EVENT_BUS;
    }

    /** One subscribed method, removable. */
    static final class Subscription {
        final Object listener;
        private final Consumer<Event> handler;
        /**
         * Set when removed. The bus may already hold its listener array while dispatching (plugins reload from inside
         * a tick event), so the handler checks this and never calls into an unloaded plugin, even from that array.
         */
        private volatile boolean removed;

        private Subscription(Object listener, Consumer<Event> handler) {
            this.listener = listener;
            this.handler = handler;
        }

        void remove() {
            removed = true;
            bus().unregister(handler);
        }
    }

    /** Subscribes every {@code @SubscribeEvent} method; all or nothing. */
    static List<Subscription> subscribe(Object listener, String plugin, Logger log) {
        List<Subscription> out = new ArrayList<>();
        try {
            for (Method m : methods(listener.getClass())) {
                SubscribeEvent sub = m.getAnnotation(SubscribeEvent.class);
                if (sub != null) out.add(subscribe(listener, m, sub, plugin, log));
            }
        } catch (RuntimeException e) {
            out.forEach(Subscription::remove);
            throw e;
        }
        return out;
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Subscription subscribe(Object listener, Method m, SubscribeEvent sub, String plugin, Logger log) {
        String where = plugin + ": " + m.getDeclaringClass().getSimpleName() + "." + m.getName();
        Class<?>[] params = m.getParameterTypes();
        if (params.length != 1 || !Event.class.isAssignableFrom(params[0])) {
            throw new IllegalArgumentException(where + " has @SubscribeEvent but doesn't take exactly one event");
        }
        Class<? extends Event> type = (Class<? extends Event>) params[0];
        if (IModBusEvent.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException(where + " listens for " + type.getSimpleName() + ", a mod bus event. "
                    + "Those fire during startup, before any plugin loads, so a plugin never hears them.");
        }
        boolean isStatic = Modifier.isStatic(m.getModifiers());
        MethodHandle handle;
        try {
            m.setAccessible(true);
            MethodHandle h = MethodHandles.lookup().unreflect(m);
            handle = isStatic ? h : h.bindTo(listener);
        } catch (IllegalAccessException | RuntimeException e) {
            throw new IllegalArgumentException(where + " can't be called by Hadron: " + e.getMessage(), e);
        }
        AtomicInteger failures = new AtomicInteger();
        Subscription[] self = new Subscription[1];
        Consumer<Event> handler = event -> {
            if (self[0].removed) return;
            try {
                handle.invoke(event);
            } catch (Throwable t) {
                // A plugin's bug costs that plugin its event, not the server its tick. Logged in full a few times,
                // then counted, so a listener on every tick can't flood the log.
                int n = failures.incrementAndGet();
                if (n <= 5) {
                    log.error(where + " threw on " + event.getClass().getSimpleName(), t);
                } else if (n % 1000 == 0) {
                    log.error("{} has thrown {} times; last: {}", where, n, t.toString());
                }
            }
        };
        self[0] = new Subscription(listener, handler);
        try {
            bus().addListener(sub.priority(), sub.receiveCanceled(), (Class) type, (Consumer) handler);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(where + " can't listen for " + type.getSimpleName() + ": "
                    + e.getMessage(), e);
        }
        return self[0];
    }
}
