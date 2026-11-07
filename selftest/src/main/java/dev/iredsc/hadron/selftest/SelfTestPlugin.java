package dev.iredsc.hadron.selftest;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.server.ServerLoadEvent;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import net.minecraft.server.MinecraftServer;
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

    @Override
    public void onEnable() {
        enabledAtStartup = getServer().getMinecraftServer().getTickCount() == 0;
        getServer().getPluginManager().registerEvents(this, this);
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
