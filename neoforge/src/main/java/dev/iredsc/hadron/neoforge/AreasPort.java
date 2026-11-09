package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.api.world.Area;
import dev.iredsc.hadron.api.world.Areas;
import dev.iredsc.hadron.common.PluginHost;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * The NeoForge side of {@link Areas}. The areas themselves live on their levels ({@link AreaIndex}), so this holds
 * nothing: a level that closes takes its areas with it ({@code AreasServerLevelMixin}), and a plugin that disables
 * has its areas closed.
 */
final class AreasPort implements Areas.Backend {
    private AreasPort() {}

    static void register() {
        // Before Hadron enables plugins on the same event, so onEnable can watch.
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH,
                (ServerStartingEvent e) -> Areas.Port.install(new AreasPort()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> Areas.Port.install(null));
        PluginHost.onPluginDisable(plugin -> {
            PluginHost host = Hooks.host();
            if (host == null) return;
            for (ServerLevel level : host.server().getMinecraftServer().getAllLevels()) {
                AreaIndex.closeAll(level, plugin);
            }
        });
    }

    @Override
    public Area watch(JavaPlugin plugin, ServerLevel level, BoundingBox box) {
        requireServerThread(level, "watch an area");
        if (!plugin.isEnabled()) {
            throw new IllegalStateException(plugin.getName() + " isn't enabled, so it can't watch an area: an area "
                    + "lasts only while its plugin runs. Watch it in onEnable or later.");
        }
        if (level.getServer().getLevel(level.dimension()) != level) {
            throw new IllegalArgumentException("The world " + level.dimension().location() + " isn't loaded, so "
                    + "nothing in it can be watched. Load it with Worlds.load first.");
        }
        return AreaIndex.watch(plugin, level, box);
    }

    @Override
    public boolean isWatched(ServerLevel level, BlockPos pos) {
        requireServerThread(level, "ask whether a position is watched");
        return AreaIndex.covers(level, pos);
    }

    static void requireServerThread(ServerLevel level, String what) {
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("Areas are kept on the server thread, so you can't " + what + " from "
                    + Thread.currentThread().getName() + ". Run it in a sync task: "
                    + "Hadron.getScheduler().runTask(plugin, ...).");
        }
    }
}
