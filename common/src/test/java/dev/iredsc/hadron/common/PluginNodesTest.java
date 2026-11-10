package dev.iredsc.hadron.common;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iredsc.hadron.api.Default;
import dev.iredsc.hadron.api.Node;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.test.Covers;
import org.junit.jupiter.api.Test;

/**
 * A plugin version declares nodes while it loads and runs, never once it starts unloading: a background task that
 * outlives its plugin can't put a node back after the host released them, which would keep the old loader forever.
 */
class PluginNodesTest {
    @Covers(value = Node.class, method = "declare")
    @Test
    void aLoadedPluginDeclaresAndAnUnloadingOneCant() {
        JavaPlugin plugin = TestPlugins.create("Gone", null);
        ClassLoader loader = plugin.getClass().getClassLoader();
        assertNotNull(Node.Lifecycle.declare(TestHost.KEY, "gone.early", Default.OPS, "", loader),
                "while loaded, as in onLoad");

        TestPlugins.beginUnload(plugin);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Node.Lifecycle.declare(TestHost.KEY, "gone.late", Default.OPS, "", loader));
        assertTrue(e.getMessage().contains("gone.late") && e.getMessage().contains("Gone")
                && e.getMessage().contains("onDisable"), e.getMessage());
        assertNull(Node.get("gone.late"), "a refused declaration leaves nothing behind");

        Node.Lifecycle.release(TestHost.KEY, loader);
        assertThrows(IllegalStateException.class,
                () -> Node.Lifecycle.declare(TestHost.KEY, "gone.early", Default.OPS, "", loader));
        assertNull(Node.get("gone.early"), "released, and not put back");
    }
}
