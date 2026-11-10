package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.HostKey;
import dev.iredsc.hadron.checks.StaticState;
import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.world.Worlds;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The NeoForge side of worlds created, loaded and unloaded while the server runs ({@link WorldsManager}). Mixins in
 * {@code mixin.worlds}: the server's executor and storage, per-world seeds, and unloading without saving.
 */
final class WorldsPort {
    /** Server thread only. */
    @StaticState(reason = "the running server's created worlds: made when it starts, cleared when it stops")
    private static @Nullable WorldsManager manager;

    private WorldsPort() {}

    static void register(HostKey key) {
        // Before HadronMod enables plugins (NORMAL), so created worlds exist in onEnable.
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, (ServerStartingEvent e) -> starting(key, e));
        // After the scheduler's tasks (HadronMod's tick), so a world unloaded in a task closes the same tick.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, WorldsPort::tick);
        // After plugins disable, so worlds they unload on the way out close before the server saves and stops.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, WorldsPort::stopping);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (ServerStoppedEvent e) -> stopped(key, e));
    }

    private static void starting(HostKey key, ServerStartingEvent event) {
        manager = new WorldsManager(event.getServer());
        manager.restore();
        Worlds.Port.install(key, manager);
    }

    private static void tick(ServerTickEvent.Post event) {
        if (manager != null) manager.finishClosing();
    }

    private static void stopping(ServerStoppingEvent event) {
        if (manager != null) manager.finishClosing();
    }

    private static void stopped(HostKey key, ServerStoppedEvent event) {
        if (manager != null) manager.finishClosing();
        Worlds.Port.install(key, null);
        manager = null;
        WorldsHooks.clearSeeds();
    }
}
