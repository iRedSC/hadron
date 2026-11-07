package dev.iredsc.hadron.selftest;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.server.ServerLoadEvent;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import net.minecraft.server.MinecraftServer;

/**
 * The plugin Hadron's own tests run as: they register listeners and hide players in its name. It also records what it
 * heard while the server started, before any test could listen, so {@code ServerLoadTests} can check it.
 */
public final class SelfTestPlugin extends JavaPlugin implements Listener {
    /** Whether this copy was enabled as the server started, rather than by a reload afterwards. */
    public static volatile boolean enabledAtStartup;
    /** How many ServerLoadEvents this copy heard, and the server the last one named. */
    public static volatile int serverLoads;
    public static volatile MinecraftServer loadedServer;

    @Override
    public void onEnable() {
        enabledAtStartup = getServer().getMinecraftServer().getTickCount() == 0;
        serverLoads = 0;
        loadedServer = null;
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    private void loaded(ServerLoadEvent event) {
        serverLoads++;
        loadedServer = event.getServer();
    }
}
