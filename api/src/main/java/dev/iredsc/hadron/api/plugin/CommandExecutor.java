package dev.iredsc.hadron.api.plugin;

import net.minecraft.commands.CommandSourceStack;

/** Runs a command declared in plugin.yml. Bind one with {@code getCommand("name").setExecutor(...)}. */
public interface CommandExecutor {
    /**
     * @param sender who ran it: {@code sender.getPlayer()} is the player, or null for the console and command blocks.
     *     Reply with {@code sender.sendSuccess(() -> Component.literal("..."), false)} or {@code sender.sendFailure(...)}.
     * @param label the name or alias the sender typed.
     * @param args the words after the command, split on spaces.
     * @return false to show the sender the command's usage.
     */
    boolean onCommand(CommandSourceStack sender, PluginCommand command, String label, String[] args);
}
