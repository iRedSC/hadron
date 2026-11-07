package dev.iredsc.hadron.neoforge;

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
    private static WorldsManager manager;

    private WorldsPort() {}

    static void register() {
        // Before HadronMod enables plugins (NORMAL), so created worlds exist in onEnable.
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, WorldsPort::starting);
        // After the scheduler's tasks (HadronMod's tick), so a world unloaded in a task closes the same tick.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, WorldsPort::tick);
        // After plugins disable, so worlds they unload on the way out close before the server saves and stops.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, WorldsPort::stopping);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, WorldsPort::stopped);
    }

    private static void starting(ServerStartingEvent event) {
        manager = new WorldsManager(event.getServer());
        manager.restore();
        Worlds.Port.install(manager);
    }

    private static void tick(ServerTickEvent.Post event) {
        if (manager != null) manager.finishClosing();
    }

    private static void stopping(ServerStoppingEvent event) {
        if (manager != null) manager.finishClosing();
    }

    private static void stopped(ServerStoppedEvent event) {
        if (manager != null) manager.finishClosing();
        Worlds.Port.install(null);
        manager = null;
        WorldsHooks.clearSeeds();
    }
}
