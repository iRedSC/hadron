package dev.iredsc.hadron.api.plugin;

import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.Node;
import dev.iredsc.hadron.api.Permissions;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;

/**
 * A command declared in plugin.yml. Runs its {@link CommandExecutor} (the plugin itself until {@link #setExecutor}
 * says otherwise). Anyone without its {@code permission} doesn't see it at all, as with vanilla commands.
 */
public final class PluginCommand {
    private final JavaPlugin plugin;
    private final PluginDescription.CommandSpec spec;
    private volatile CommandExecutor executor;
    private volatile TabCompleter completer;

    public PluginCommand(JavaPlugin plugin, PluginDescription.CommandSpec spec) {
        this.plugin = plugin;
        this.spec = spec;
        this.executor = plugin;
        this.completer = plugin;
    }

    /** Null restores the plugin's own onCommand. */
    public void setExecutor(@Nullable CommandExecutor executor) {
        this.executor = executor == null ? plugin : executor;
    }

    /** Null restores the plugin's own onTabComplete. */
    public void setTabCompleter(@Nullable TabCompleter completer) {
        this.completer = completer == null ? plugin : completer;
    }

    public CommandExecutor getExecutor() {
        return executor;
    }

    public TabCompleter getTabCompleter() {
        return completer;
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }

    public String getName() {
        return spec.name;
    }

    public String getDescription() {
        return spec.description;
    }

    public String getUsage() {
        return spec.usage;
    }

    public List<String> getAliases() {
        return spec.aliases;
    }

    /** Null when anyone may run it. */
    public @Nullable String getPermission() {
        return spec.permission;
    }

    /** Whether the sender holds the command's permission, if it has one. */
    public boolean testPermissionSilent(CommandSourceStack sender) {
        if (spec.permission == null) return true;
        Node node = Node.get(spec.permission);
        return node != null && Permissions.has(sender, node);
    }
}
