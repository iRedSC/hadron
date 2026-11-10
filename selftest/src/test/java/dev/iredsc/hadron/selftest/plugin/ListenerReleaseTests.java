package dev.iredsc.hadron.selftest.plugin;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.block.BlockBreakEvent;
import dev.iredsc.hadron.api.plugin.PluginManager;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import java.lang.ref.WeakReference;

/**
 * A listener a plugin unregisters is let go at once, not kept until the plugin disables: a plugin that registers
 * and unregisters listeners as it runs (one per game, one per open menu) would otherwise keep every one, and all
 * they hold, for as long as it runs.
 */
public final class ListenerReleaseTests {
    /** Built in its own method, so the test's frame holds no reference to it. */
    private static WeakReference<Listener> registerAndUnregister(TestContext t) {
        PluginManager plugins = t.plugin().getServer().getPluginManager();
        Listener listener = new Listener() {
            @EventHandler
            void on(BlockBreakEvent e) {}
        };
        plugins.registerEvents(listener, t.plugin());
        plugins.unregisterEvents(listener);
        return new WeakReference<>(listener);
    }

    @HadronTest
    @Covers(value = PluginManager.class, method = "unregisterEvents")
    void anUnregisteredListenerIsLetGo(TestContext t) {
        WeakReference<Listener> gone = registerAndUnregister(t);
        for (int i = 0; i < 20 && gone.get() != null; i++) {
            System.gc();
            t.tick();
        }
        t.assertTrue(gone.get() == null, "the plugin's unregistered listener is still held, so it and everything it "
                + "refers to stay in memory until the plugin disables");
    }
}
