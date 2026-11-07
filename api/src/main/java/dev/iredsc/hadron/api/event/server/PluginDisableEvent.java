package dev.iredsc.hadron.api.event.server;

import dev.iredsc.hadron.api.plugin.JavaPlugin;

/** A plugin has just disabled, after its onDisable; its listeners no longer hear events. Not cancellable. */
public class PluginDisableEvent extends ServerEvent {
    private final JavaPlugin plugin;

    public PluginDisableEvent(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }
}
