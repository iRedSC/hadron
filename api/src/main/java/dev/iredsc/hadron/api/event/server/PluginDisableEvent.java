package dev.iredsc.hadron.api.event.server;

import dev.iredsc.hadron.api.event.FiredBy;
import dev.iredsc.hadron.api.plugin.JavaPlugin;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A plugin has just disabled, after its onDisable; its listeners no longer hear events. Not cancellable.
 * By the time this fires the host already treats it as gone: {@code isPluginEnabled(name)} is false and
 * {@code getPlugin(name)} no longer returns this instance, so a listener recomputing what's available sees it absent.
 */
@FiredBy(core = FULL)
public class PluginDisableEvent extends ServerEvent {
    private final JavaPlugin plugin;

    public PluginDisableEvent(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }
}
