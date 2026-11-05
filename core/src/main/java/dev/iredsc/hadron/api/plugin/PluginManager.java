package dev.iredsc.hadron.api.plugin;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.neoforged.bus.api.Event;

/**
 * Plugins and what they register, like Bukkit's plugin manager. Everything registered here belongs to the plugin
 * passed in and is undone when it disables, so a reload never leaves an old listener or command behind.
 */
public interface PluginManager {
    /**
     * Subscribes every {@code @SubscribeEvent} method of {@code listener} (NeoForge's annotation, with its priority and
     * {@code receiveCanceled}) on NeoForge's game event bus. Methods may have any visibility, and may be static.
     *
     * @throws IllegalArgumentException for a method that doesn't take exactly one event, or an event plugins can't
     *     hear (mod bus events, which fire during startup before any plugin loads).
     */
    void registerEvents(Object listener, JavaPlugin plugin);

    /** Removes every listener {@code listener} registered. */
    void unregisterEvents(Object listener);

    /**
     * Adds a Brigadier command, for commands with typed arguments, or ports that already have one. A name another
     * command already has is taken over until this plugin disables, then given back.
     */
    void registerCommand(LiteralArgumentBuilder<CommandSourceStack> command, JavaPlugin plugin);

    /** Posts the event on NeoForge's game bus. @return whether it was cancelled. */
    boolean callEvent(Event event);

    /** Case-insensitive. Null unless a plugin by that name is loaded (in onLoad or later) or enabled. */
    JavaPlugin getPlugin(String name);

    /** Every enabled plugin, in the order they enabled. */
    List<JavaPlugin> getPlugins();

    boolean isPluginEnabled(String name);
}
