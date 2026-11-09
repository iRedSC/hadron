package dev.iredsc.hadron.selftest;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.server.ServerLoadEvent;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.api.world.Area;
import dev.iredsc.hadron.api.world.Areas;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * The plugin Hadron's own tests run as: they register listeners and hide players in its name. It also records what it
 * heard while the server started, before any test could listen, so {@code ServerLoadTests} can check it.
 */
public final class SelfTestPlugin extends JavaPlugin implements Listener {
    // On the instance, not static: each reload makes a new copy, which starts from nothing, and the old one's state
    // goes with it.
    private volatile boolean enabledAtStartup;
    private volatile int serverLoads;
    private volatile @Nullable MinecraftServer loadedServer;
    private volatile @Nullable Area startupArea;

    @Override
    public void onEnable() {
        enabledAtStartup = getServer().getMinecraftServer().getTickCount() == 0;
        // Where regions plugins watch their regions: onEnable, which on a cold start runs while the server starts.
        // Far from anything a test touches.
        startupArea = Areas.watch(this, getServer().getMinecraftServer().overworld(),
                BoundingBox.fromCorners(new BlockPos(900_000, 0, 900_000), new BlockPos(900_001, 1, 900_001)));
        getServer().getPluginManager().registerEvents(this, this);
    }

    /** The area this copy watched in onEnable. */
    public @Nullable Area startupArea() {
        return startupArea;
    }

    /** Whether this copy was enabled as the server started, rather than by a reload afterwards. */
    public boolean enabledAtStartup() {
        return enabledAtStartup;
    }

    /** How many ServerLoadEvents this copy heard. */
    public int serverLoads() {
        return serverLoads;
    }

    /** The server the last ServerLoadEvent this copy heard named, or null if it heard none. */
    public @Nullable MinecraftServer loadedServer() {
        return loadedServer;
    }

    @EventHandler
    private void loaded(ServerLoadEvent event) {
        serverLoads++;
        loadedServer = event.getServer();
    }
}
