package dev.iredsc.hadron.selftest.plugin;

import dev.iredsc.hadron.api.plugin.JavaPlugin;

/**
 * Not a test: the main class of a plugin jar {@link HostLifecycleTests} builds. Its {@code onLoad} runs whatever
 * {@link #ON_LOAD} holds, so a test can change files in plugins/ at the moment the host is loading plugins, between
 * one plugin's onLoad and the next one's load.
 */
public final class LoadSwapPlugin extends JavaPlugin {
    /** A system property holding a {@link Runnable}, which survives the plugin's own class loader. */
    public static final String ON_LOAD = "hadron.selftest.loadSwap";

    @Override
    public void onLoad() {
        if (System.getProperties().get(ON_LOAD) instanceof Runnable action) action.run();
    }
}
