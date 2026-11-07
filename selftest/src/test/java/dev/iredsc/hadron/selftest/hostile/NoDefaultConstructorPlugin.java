package dev.iredsc.hadron.selftest.hostile;

import dev.iredsc.hadron.api.plugin.JavaPlugin;

/** Not a test: a plugin main class Hadron can't create, since its only constructor takes an argument. */
public final class NoDefaultConstructorPlugin extends JavaPlugin {
    public NoDefaultConstructorPlugin(String name) {}
}
