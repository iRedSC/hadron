package dev.iredsc.hadron.common;

import dev.iredsc.hadron.api.plugin.JavaPlugin;

/** A plugin that does nothing. {@link TestPlugins} loads a fresh copy per plugin, the way Hadron loads a jar. */
public final class StubPlugin extends JavaPlugin {
    public StubPlugin() {}
}
