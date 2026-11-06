package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.common.PluginHost;
import java.util.function.Supplier;

/**
 * How the port's listeners and mixins reach the running plugin host. Every chokepoint asks {@link #listens} before
 * building an event, so a server where no plugin listens pays almost nothing.
 */
public final class Hooks {
    private static volatile Supplier<PluginHost> hosts = () -> null;

    private Hooks() {}

    static void install(Supplier<PluginHost> source) {
        hosts = source;
    }

    /** The running host, or null between servers. */
    public static PluginHost host() {
        return hosts.get();
    }

    /** Whether a plugin hears this event class right now. False between servers. */
    public static boolean listens(Class<? extends Event> type) {
        PluginHost host = hosts.get();
        return host != null && host.listens(type);
    }

    /** Fires the event if a host is running, and returns it so the caller can read what listeners changed. */
    public static <E extends Event> E post(E event) {
        PluginHost host = hosts.get();
        if (host == null) return event;
        // A handler's own block changes are its own actions, checked like any other: the suppression that keeps
        // this event's effect from being reported twice must not hide them.
        long saved = BlockHooks.pauseSuppression();
        try {
            host.post(event);
        } finally {
            BlockHooks.resumeSuppression(saved);
        }
        return event;
    }
}
