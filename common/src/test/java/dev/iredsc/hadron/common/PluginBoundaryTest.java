package dev.iredsc.hadron.common;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iredsc.hadron.api.Hadron;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * A plugin's class loader links Hadron's api, never its insides: the host's and the port's classes are public only
 * so Hadron's own modules and mixins reach each other.
 */
class PluginBoundaryTest {
    private final ClassLoader plugin = TestPlugins.create("Boundary", null).getClass().getClassLoader();

    @ParameterizedTest
    @ValueSource(strings = {"dev.iredsc.hadron.common.PluginHost", "dev.iredsc.hadron.neoforge.Hooks",
            "dev.iredsc.hadron.harness.TestRunner", "dev.iredsc.hadron.common.economy.EconomyCore"})
    void aPluginCanNotLinkHadronsInsidesAndIsToldToUseTheApi(String host) {
        ClassNotFoundException e = assertThrows(ClassNotFoundException.class, () -> plugin.loadClass(host));
        assertTrue(e.getMessage().startsWith(host + " is inside Hadron's plugin host"), e.getMessage());
        assertTrue(e.getMessage().contains("only Hadron's api (dev.iredsc.hadron.api)"), e.getMessage());
    }

    @Test
    void aPluginStillLinksTheApiAndItsOwnClasses() throws Exception {
        assertSame(Hadron.class, plugin.loadClass(Hadron.class.getName()), "the one api every plugin shares");
        assertSame(JavaPlugin.class, plugin.loadClass(JavaPlugin.class.getName()));
        // TestPlugins' stub lives in a host package, yet comes from the plugin's own jar: only the parent is closed.
        Class<?> own = plugin.loadClass(StubPlugin.class.getName());
        assertSame(plugin, own.getClassLoader(), "the plugin's own copy, not the host's");
    }
}
