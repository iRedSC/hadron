package dev.iredsc.hadron.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.api.plugin.PluginCommand;
import dev.iredsc.hadron.api.plugin.PluginContext;
import dev.iredsc.hadron.api.plugin.PluginDescription;
import dev.iredsc.hadron.api.plugin.PluginException;
import dev.iredsc.hadron.api.plugin.Server;
import java.io.File;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;

/** Permission nodes: their shape, the roots Hadron reserves, and who owns a declaration. Node state is per JVM. */
class NodeTest {
    private static final AtomicInteger IDS = new AtomicInteger();

    /** A fresh node id, so tests never see each other's declarations. */
    private static String id() {
        return "nodetest" + IDS.incrementAndGet() + ".thing";
    }

    /** Stands in for a plugin's class loader: Node recognises plugins by their loader. */
    private static final class PluginLoaderStandIn extends ClassLoader implements PluginContext {
        private final PluginDescription description = PluginDescription.parse(Map.of(
                "name", "Homes", "version", "1", "main", "a.B", "minecraft", "1.21.1"));

        @Override
        public PluginDescription description() {
            return description;
        }

        @Override
        public File file() {
            return null;
        }

        @Override
        public File dataFolder() {
            return null;
        }

        @Override
        public Logger logger() {
            return null;
        }

        @Override
        public Server server() {
            return null;
        }

        @Override
        public void attach(JavaPlugin plugin) {}

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public PluginCommand command(String name) {
            return null;
        }

        @Override
        public InputStream resource(String path) {
            return null;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"homes", "Homes.set", "homes.", ".set", "homes..set", "homes.se t", "homes.*"})
    void aMalformedIdIsRefused(String id) {
        // COVERS: Node#declare
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Node.declare(id, Default.OPS, ""));
        assertTrue(e.getMessage().contains("plugin.thing"), e.getMessage());
    }

    @Test
    void aDeclaredNodeIsFoundByIdAndListed() {
        // COVERS: Node#declare
        // COVERS: Node#get
        // COVERS: Node#all
        String id = id();
        Node node = Node.declare(id, Default.EVERYONE, "Do the thing");
        assertEquals(id, node.id);
        assertEquals(Default.EVERYONE, node.fallback);
        assertEquals("Do the thing", node.description);
        assertSame(node, Node.get(id));
        assertTrue(Node.all().contains(node));
        assertEquals(id, node.toString());
        assertNull(Node.get("nodetest.never"));
    }

    @Test
    void declaringTwiceWithTheSameDefaultGivesTheFirstNode() {
        // COVERS: Node#declare
        String id = id();
        assertSame(Node.declare(id, Default.OPS, "a"), Node.declare(id, Default.OPS, "b"));
    }

    @Test
    void declaringTwiceWithDifferentDefaultsIsRefusedNamingBoth() {
        // COVERS: Node#declare
        String id = id();
        Node.declare(id, Default.OPS, "");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Node.declare(id, Default.EVERYONE, ""));
        assertTrue(e.getMessage().contains("OPS") && e.getMessage().contains("EVERYONE"), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"use", "place", "break", "open", "attack", "command", "packet", "hadron"})
    void aPluginCantDeclareUnderAReservedRoot(String root) {
        // COVERS: Node#RESERVED_ROOTS
        // COVERS: Node.Lifecycle#declare
        PluginException e = assertThrows(PluginException.class, () -> Node.Lifecycle.declare(root + ".x",
                Default.OPS, "", new PluginLoaderStandIn()));
        assertTrue(e.getMessage().contains("such as homes.x"), e.getMessage());
    }

    @Test
    void hadronItselfMayDeclareUnderAReservedRoot() {
        // COVERS: Node#RESERVED_ROOTS
        assertNotNull(Node.Lifecycle.declare("hadron.nodetest" + IDS.incrementAndGet(), Default.OPS, "", null));
    }

    @Test
    void aReservedWordFurtherInIsFine() {
        // COVERS: Node#RESERVED_ROOTS
        assertNotNull(Node.Lifecycle.declare("homes.use" + IDS.incrementAndGet(), Default.OPS, "",
                new PluginLoaderStandIn()));
    }

    @Test
    void anImplicitNodeGivesWayToTheExplicitDeclaration() {
        // COVERS: Node.Lifecycle#declareImplicit
        String id = id();
        ClassLoader owner = new PluginLoaderStandIn();
        Node implicit = Node.Lifecycle.declareImplicit(id, "command permission", owner);
        assertEquals(Default.OPS, implicit.fallback);
        Node explicit = Node.Lifecycle.declare(id, Default.EVERYONE, "real", owner);
        assertNotSame(implicit, explicit);
        assertEquals(Default.EVERYONE, Node.get(id).fallback);
        // An implicit declaration after the explicit one keeps the explicit one.
        assertSame(explicit, Node.Lifecycle.declareImplicit(id, "again", owner));
    }

    @Test
    void releasingTheLastOwnerRemovesTheNode() {
        // COVERS: Node.Lifecycle#release
        String id = id();
        ClassLoader first = new PluginLoaderStandIn();
        ClassLoader second = new PluginLoaderStandIn();
        Node.Lifecycle.declare(id, Default.OPS, "", first);
        Node.Lifecycle.declare(id, Default.OPS, "", second);
        Node.Lifecycle.release(first);
        assertNotNull(Node.get(id), "another plugin version still declares it");
        Node.Lifecycle.release(second);
        assertNull(Node.get(id));
    }

    @Test
    void aDeclarationWithNoClassLoaderIsPermanent() {
        // COVERS: Node.Lifecycle#release
        String id = id();
        Node.Lifecycle.declare(id, Default.OPS, "", null);
        Node.Lifecycle.release(null);
        Node.Lifecycle.release(getClass().getClassLoader());
        assertNotNull(Node.get(id));
    }
}
