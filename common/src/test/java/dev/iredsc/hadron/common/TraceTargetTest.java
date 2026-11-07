package dev.iredsc.hadron.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** A trace of a plugin's own event follows the plugin across reloads, whichever version's class it was named from. */
class TraceTargetTest {
    @Test
    void aPluginEventTraceHearsTheReloadedVersionsClass() throws Exception {
        Class<?> old = TestPlugins.event(TestPlugins.create("Shop", null));
        Class<?> reloaded = TestPlugins.event(TestPlugins.create("Shop", null));
        assertNotSame(old, reloaded, "each version defines its own class");
        Trace.Target target = Trace.target(old.getName(), Set.of(old), name -> null);
        assertTrue(target.hears().test(old));
        assertTrue(target.hears().test(reloaded), "a reload mustn't leave the trace listening to a dead class");
        assertFalse(target.hears().test(EventBusTest.Ping.class));
    }

}
