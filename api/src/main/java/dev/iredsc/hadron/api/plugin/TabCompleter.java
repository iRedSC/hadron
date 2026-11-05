package dev.iredsc.hadron.api.plugin;

import java.util.List;
import net.minecraft.commands.CommandSourceStack;

/** Completes a command declared in plugin.yml. */
public interface TabCompleter {
    /**
     * @param args the words typed so far; the last one is the word being completed, and may be empty.
     * @return the candidates for the last word (Hadron keeps those starting with what's typed), or null for online
     *     player names.
     */
    List<String> onTabComplete(CommandSourceStack sender, PluginCommand command, String label, String[] args);
}
