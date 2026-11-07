package dev.iredsc.hadron.api.event.server;

import dev.iredsc.hadron.api.plugin.JavaPlugin;

/**
 * A plugin has just disabled, after its onDisable; its listeners no longer hear events. Not cancellable.
 * By the time this fires the host already treats it as gone: {@code isPluginEnabled(name)} is false and
 * {@code getPlugin(name)} no longer returns this instance, so a listener recomputing what's available sees it absent.
 */
public class PluginDisableEvent extends ServerEvent {
    private final JavaPlugin plugin;

    public PluginDisableEvent(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }
}
