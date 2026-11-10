package dev.iredsc.hadron.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iredsc.hadron.api.Default;
import dev.iredsc.hadron.api.Hadron;
import dev.iredsc.hadron.api.Node;
import dev.iredsc.hadron.api.PermissionProvider;
import dev.iredsc.hadron.api.Permissions;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.api.plugin.Server;
import dev.iredsc.hadron.api.plugin.ServicePriority;
import dev.iredsc.hadron.test.Covers;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.minecraft.server.level.ServerPlayer;

/** A permissions plugin's bug never reaches whoever asked: a node falls back to its default, as with no provider. */
class PermissionsTest {
    /** A provider whose every answer throws, as a broken or half-unloaded permissions plugin would. */
    static final class Broken implements PermissionProvider {
        @Override
        public Boolean check(ServerPlayer player, String node) {
            throw new IllegalStateException("broken check");
        }

        @Override
        public String group(ServerPlayer player) {
            throw new IllegalStateException("broken group");
        }

        @Override
        public boolean inGroup(ServerPlayer player, String group) {
            throw new IllegalStateException("broken inGroup");
        }

        @Override
        public String prefix(ServerPlayer player) {
            throw new NoClassDefFoundError("broken/Prefix");
        }

        @Override
        public String suffix(ServerPlayer player) {
            throw new IllegalStateException("broken suffix");
        }
    }

    @TempDir
    Path dir;
    private Server before;

    @BeforeEach
    void installBrokenProvider() {
        PluginHost host = ServicesTest.hostWithoutServer(dir.toFile());
        JavaPlugin ranks = TestPlugins.create("Ranks", host.server());
        host.services().register(PermissionProvider.class, new Broken(), ranks, ServicePriority.Normal);
        before = Hadron.Lifecycle.running();
        Hadron.Lifecycle.install(TestHost.KEY, host.server());
    }

    @AfterEach
    void restore() {
        Hadron.Lifecycle.install(TestHost.KEY, before);
    }

    @Covers(value = Permissions.class, method = "has")
    @Test
    void aThrowingProviderFallsBackToTheNodesDefault() {
        Node everyone = Node.declare("permissionstest.everyone", Default.EVERYONE, "");
        Node nobody = Node.declare("permissionstest.nobody", Default.NOBODY, "");
        assertTrue(Permissions.has((ServerPlayer) null, everyone));
        assertFalse(Permissions.has((ServerPlayer) null, nobody));
    }

    @Covers(value = Permissions.class, method = "group")
    @Covers(value = Permissions.class, method = "inGroup")
    @Covers(value = Permissions.class, method = "prefix")
    @Covers(value = Permissions.class, method = "suffix")
    @Test
    void aThrowingProviderGivesNoRanks() {
        assertEquals(Permissions.DEFAULT_GROUP, Permissions.group(null));
        assertTrue(Permissions.inGroup(null, "default"));
        assertFalse(Permissions.inGroup(null, "admin"));
        assertEquals("", Permissions.prefix(null));
        assertEquals("", Permissions.suffix(null));
    }
}
