package dev.iredsc.hadron.api.plugin;

/**
 * A plugin's main class, named in its {@code plugin.yml} as {@code main}.
 *
 * <p>Named and shaped like Bukkit's so an agent's first guess is right. Everything a plugin registers is undone when it
 * disables, so {@link #onDisable()} only needs to save.
 */
public abstract class JavaPlugin {
    /** Before any plugin enables, at startup and on each reload. Register placeholders and services here. */
    public void onLoad() {}

    /** After every plugin it depends on is enabled. Register listeners, commands and tasks here. */
    public void onEnable() {}

    /** On every reload and at shutdown. Save here; registrations are undone afterwards. */
    public void onDisable() {}
}
