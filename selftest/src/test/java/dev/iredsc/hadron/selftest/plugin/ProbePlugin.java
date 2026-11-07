package dev.iredsc.hadron.selftest.plugin;

import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.api.plugin.PluginCommand;
import java.util.Arrays;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;

/**
 * Not a test: the main class of a plugin jar {@link PluginLifecycleTests} builds and drops into {@code plugins/}, so
 * Hadron loads it with its own class loader like any plugin. It writes what happens to it into a list the test puts in
 * the system properties, the one place both class loaders share. Kept free of lambdas and inner classes, so this one
 * class file is the whole plugin.
 */
public final class ProbePlugin extends JavaPlugin {
    static final String LOG = "hadron.selftest.probe.log";

    @SuppressWarnings("unchecked")
    private static void log(String line) {
        Object log = System.getProperties().get(LOG);
        if (log instanceof List) ((List<Object>) log).add(line);
    }

    @Override
    public void onLoad() {
        log("load enabled=" + isEnabled());
    }

    @Override
    public void onEnable() {
        log("enable enabled=" + isEnabled());
    }

    @Override
    public void onDisable() {
        log("disable");
    }

    @Override
    public boolean onCommand(CommandSourceStack sender, PluginCommand command, String label, String[] args) {
        log("command " + command.getName() + " " + label + " " + Arrays.toString(args));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSourceStack sender, PluginCommand command, String label, String[] args) {
        return List.of("alpha", "beta");
    }
}
