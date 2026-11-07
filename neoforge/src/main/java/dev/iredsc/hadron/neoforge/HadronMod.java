package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.Hadron;
import dev.iredsc.hadron.common.PluginHost;
import java.io.File;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Starts a plugin host with each server (a dedicated server once; singleplayer every time a world opens) and stops it
 * with the server.
 */
@Mod(HadronMod.MOD_ID)
public final class HadronMod {
    public static final String MOD_ID = "hadron";

    /** Published to background dispatcher preparation; lifecycle changes stay on the server thread. */
    private static volatile PluginHost host;

    public HadronMod(IEventBus modBus, ModContainer container) {
        NeoForge.EVENT_BUS.addListener(HadronMod::aboutToStart);
        NeoForge.EVENT_BUS.addListener(HadronMod::starting);
        NeoForge.EVENT_BUS.addListener(HadronMod::tick);
        NeoForge.EVENT_BUS.addListener(HadronMod::stopping);
        NeoForge.EVENT_BUS.addListener(HadronMod::stopped);
        // Last, so a plugin command taking over a mod's name replaces the mod's, not the other way round.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, HadronMod::commands);
        Hooks.install(() -> host);
        BlockEvents.register();
        EntityEvents.register();
        ItemEvents.register();
        WorldEvents.register();
        DataPort.register();
        NetPort.register();
        WorldsPort.register();
    }

    private static void aboutToStart(ServerAboutToStartEvent event) {
        MinecraftServer server = event.getServer();
        // Absolute: the server directory is often an empty path, and new File("", "plugins") means /plugins.
        File dir = server.getServerDirectory().toAbsolutePath().normalize().toFile();
        host = new PluginHost(server, dir, new NeoForgePlatform());
        Hadron.Lifecycle.install(host.server());
        host.load();
    }

    private static void starting(ServerStartingEvent event) {
        host.start();
    }

    private static void tick(ServerTickEvent.Post event) {
        if (host != null) host.tick();
    }

    private static void stopping(ServerStoppingEvent event) {
        if (host != null) host.stop();
    }

    private static void stopped(ServerStoppedEvent event) {
        if (host != null) host.stop();
        host = null;
        Hadron.Lifecycle.install(null);
    }

    /** Every new dispatcher: at startup, and on each datapack /reload. */
    private static void commands(RegisterCommandsEvent event) {
        PluginHost.registerCommands(event.getDispatcher(), () -> host);
    }
}
