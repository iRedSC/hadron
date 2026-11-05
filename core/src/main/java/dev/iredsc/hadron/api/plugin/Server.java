package dev.iredsc.hadron.api.plugin;

import java.io.File;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The running server, shaped like Bukkit's {@code Server} but with Minecraft's own types: players are
 * {@link ServerPlayer}, senders are {@link CommandSourceStack}.
 */
public interface Server {
    PluginManager getPluginManager();

    Scheduler getScheduler();

    ServicesManager getServicesManager();

    MinecraftServer getMinecraftServer();

    List<ServerPlayer> getOnlinePlayers();

    /** Exact name, ignoring case; null when they're offline. */
    ServerPlayer getPlayer(String name);

    /** Null when they're offline. */
    ServerPlayer getPlayer(UUID id);

    /** Sends to every player and the console. {@code &} color codes work. */
    void broadcastMessage(String message);

    /** Runs a command line, with or without the slash, as the sender. @return whether a command by that name exists. */
    boolean dispatchCommand(CommandSourceStack sender, String commandLine);

    /** The server console, at the highest permission level. */
    CommandSourceStack getConsoleSender();

    /**
     * Whether the server is shutting down. In onDisable, tells a shutdown (say goodbye) from a reload (just let go:
     * the new version takes over at once).
     */
    boolean isStopping();

    /** {@code plugins/}. */
    File getPluginsFolder();

    /** NeoForge's shared {@code config/}, for ports that keep their files where their mod had them. */
    File getConfigFolder();
}
