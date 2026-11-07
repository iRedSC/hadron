package dev.iredsc.hadron.api.event.server;

import dev.iredsc.hadron.api.event.FiredBy;
import dev.iredsc.hadron.api.plugin.JavaPlugin;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/** A plugin has just enabled, after its onEnable. Not cancellable. */
@FiredBy(core = FULL)
public class PluginEnableEvent extends ServerEvent {
    private final JavaPlugin plugin;

    public PluginEnableEvent(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }
}
