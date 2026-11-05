package dev.iredsc.hadron.api.plugin;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.api.event.Listener;

/**
 * Plugins and what they register, like Bukkit's plugin manager. Everything registered here belongs to the plugin
 * passed in and is undone when it disables, so a reload never leaves an old listener or command behind.
 * Registering commands and registering or removing listeners must run on the server thread; use Scheduler.runTask
 * when calling from an asynchronous task.
 */
public interface PluginManager {
    /**
     * Subscribes every {@code @EventHandler} method of {@code listener}, as in Bukkit. Events are Hadron's own
     * ({@code dev.iredsc.hadron.api.event}), the same on every loader.
     *
     * @throws IllegalArgumentException for a handler that doesn't take exactly one event.
     */
    void registerEvents(Listener listener, JavaPlugin plugin);

    /** Removes every handler {@code listener} registered. A call already in progress may finish. */
    void unregisterEvents(Listener listener);

    /**
     * Adds a Brigadier command, for commands with typed arguments, or ports that already have one. A name another
     * command already has is taken over until this plugin disables, then given back.
     */
    void registerCommand(LiteralArgumentBuilder<CommandSourceStack> command, JavaPlugin plugin);

    /** Runs every handler for the event, on this thread. @return whether it ended up cancelled. */
    boolean callEvent(Event event);

    /** Case-insensitive. Null unless a plugin by that name is loaded (in onLoad or later) or enabled. */
    JavaPlugin getPlugin(String name);

    /** Every enabled plugin, in the order they enabled. */
    List<JavaPlugin> getPlugins();

    boolean isPluginEnabled(String name);
}
