package dev.iredsc.hadron.selftest.hostile;

import dev.iredsc.hadron.api.Hadron;
import dev.iredsc.hadron.api.HostKey;
import dev.iredsc.hadron.api.economy.Economy;
import dev.iredsc.hadron.api.net.NetBackend;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.api.plugin.PluginCommand;
import dev.iredsc.hadron.api.plugin.Server;
import dev.iredsc.hadron.checks.Nondeterministic;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.commands.CommandSourceStack;

/**
 * Not a test: the main class of the broken plugin jars {@link HostileTests} builds. What it breaks is told through
 * {@link #MODE}, a system property (the one place both class loaders share), and it reports through {@link #LOG}.
 */
public final class HostilePlugin extends JavaPlugin {
    static final String MODE = "hadron.selftest.hostile.mode";
    static final String LOG = "hadron.selftest.hostile.log";
    /** An AtomicBoolean: set it to let a task that never returns finally return, so no thread outlives the run. */
    static final String RELEASE = "hadron.selftest.hostile.release";

    private static boolean is(String mode) {
        return mode.equals(System.getProperties().get(MODE));
    }

    @SuppressWarnings("unchecked")
    private static void log(String line) {
        Object log = System.getProperties().get(LOG);
        if (log instanceof List) ((List<Object>) log).add(line);
    }

    private static boolean released() {
        Object release = System.getProperties().get(RELEASE);
        return !(release instanceof AtomicBoolean b) || b.get();
    }

    public HostilePlugin() {
        if (is("constructor")) throw new IllegalStateException("hostile constructor");
    }

    @Override
    public void onLoad() {
        if (is("load")) throw new IllegalStateException("hostile onLoad");
    }

    @Override
    public void onEnable() {
        String version = getDescription().getVersion();
        log("enabled " + version);
        if (is("enable")) throw new IllegalStateException("hostile onEnable");
        if (is("listener")) getServer().getPluginManager().registerEvents(new HostileListener(), this);
        if (is("task")) {
            getServer().getScheduler().runTaskTimer(this, () -> {
                log("task ran");
                throw new IllegalStateException("hostile task");
            }, 1, 1);
        }
        if (is("forever") || is("reload")) {
            getServer().getScheduler().runTaskAsynchronously(this, this::runForever);
        }
        if (is("reload")) getServer().getScheduler().runTaskTimer(this, () -> log("tick " + version), 1, 1);
        if (is("host")) {
            // What a plugin built without Hadron's checks could call: each must be refused at runtime.
            // Where Hadron wrongly lets one through, the plugin puts things back, so the test fails instead of the run.
            Server server = Hadron.Lifecycle.running();
            Economy economy = Economy.Lifecycle.running();
            asHost("claim", () -> HostKey.claim(), () -> {});
            asHost("clear the server", () -> Hadron.Lifecycle.install(null, null),
                    () -> Hadron.Lifecycle.install(null, server));
            asHost("clear the economy", () -> Economy.Lifecycle.install(null, null),
                    () -> Economy.Lifecycle.install(null, economy));
            asHost("replace the packet backend", () -> NetBackend.Port.install(null, null), () -> {});
        }
    }

    /** Logs {@code host <what> refused: <message>}, or {@code host <what> allowed} if Hadron let it through. */
    private static void asHost(String what, Runnable call, Runnable putBack) {
        try {
            call.run();
            log("host " + what + " allowed");
            putBack.run();
        } catch (RuntimeException refused) {
            log("host " + what + " refused: " + refused.getMessage());
        }
    }

    /** The task that never returns until {@link #RELEASE}, as a plugin's runaway worker thread would. */
    @Nondeterministic(reason = "plays a plugin's runaway worker thread, which spins on its own; the test waits on the "
            + "host with ticks, not on this")
    private void runForever() {
        String version = getDescription().getVersion();
        log("forever started " + version);
        boolean triedAfterDisable = false;
        while (!released()) {
            if (!isEnabled() && !triedAfterDisable) {
                // A disabled version's leftover thread asking for server-thread work: it must never run.
                triedAfterDisable = true;
                try {
                    getServer().getScheduler().runTask(this, () -> log("stale sync task ran " + version));
                    log("stale schedule accepted " + version);
                } catch (RuntimeException refused) {
                    log("stale schedule refused " + version);
                }
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                // Never returns: that is the point. Only RELEASE ends it.
            }
        }
        log("forever returned " + version);
    }

    @Override
    public void onDisable() {
        log("disabled " + getDescription().getVersion());
        if (is("disable")) throw new IllegalStateException("hostile onDisable");
    }

    @Override
    public boolean onCommand(CommandSourceStack sender, PluginCommand command, String label, String[] args) {
        if (args.length > 0 && args[0].equals("error")) throw new AssertionError("hostile command error");
        throw new IllegalStateException("hostile command");
    }
}
