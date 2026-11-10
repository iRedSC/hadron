package dev.iredsc.hadron.api;

import dev.iredsc.hadron.checks.StaticState;
import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.plugin.PluginManager;
import dev.iredsc.hadron.api.plugin.Scheduler;
import dev.iredsc.hadron.api.plugin.Server;
import dev.iredsc.hadron.api.plugin.ServicesManager;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/**
 * Static shortcuts to the running {@link Server}, like Bukkit's {@code Bukkit} class, for code with no plugin object
 * at hand. Plugins usually go through {@code getServer()} instead.
 */
public final class Hadron {
    @StaticState(reason = "the static entry point plugins call, as Bukkit's is; the port installs the running server when it starts and clears it when it stops")
    private static volatile @Nullable Server server;

    private Hadron() {}

    /** @throws IllegalStateException before the server has started or after it stopped. */
    public static Server getServer() {
        Server s = server;
        if (s == null) throw new IllegalStateException("No server is running, so Hadron has no plugins yet");
        return s;
    }

    public static PluginManager getPluginManager() {
        return getServer().getPluginManager();
    }

    public static Scheduler getScheduler() {
        return getServer().getScheduler();
    }

    /** The economy: balances, deposits, withdrawals and transfers in any registered currency. */
    public static dev.iredsc.hadron.api.economy.Economy getEconomy() {
        return dev.iredsc.hadron.api.economy.Economy.get();
    }

    public static ServicesManager getServicesManager() {
        return getServer().getServicesManager();
    }

    public static List<ServerPlayer> getOnlinePlayers() {
        return getServer().getOnlinePlayers();
    }

    /** Exact name, ignoring case; null when they're offline. */
    public static @Nullable ServerPlayer getPlayer(String name) {
        return getServer().getPlayer(name);
    }

    /** Null when they're offline. */
    public static @Nullable ServerPlayer getPlayer(UUID id) {
        return getServer().getPlayer(id);
    }

    public static void broadcastMessage(String message) {
        getServer().broadcastMessage(message);
    }

    public static boolean isPrimaryThread() {
        return getServer().getScheduler().isPrimaryThread();
    }

    /** For Hadron's plugin host only: replacing or clearing the server takes the {@link HostKey}. */
    public static final class Lifecycle {
        private Lifecycle() {}

        public static void install(HostKey key, @Nullable Server running) {
            HostKey.require(key, "Hadron.Lifecycle.install", "read the running server with Hadron.getServer() or "
                    + "your plugin's getServer()");
            server = running;
        }

        /** Null while no server is running. */
        public static @Nullable Server running() {
            return server;
        }
    }
}
