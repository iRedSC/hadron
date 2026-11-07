package dev.iredsc.hadron.selftest.permissions;

import dev.iredsc.hadron.api.Default;
import dev.iredsc.hadron.api.Node;
import dev.iredsc.hadron.api.PermissionProvider;
import dev.iredsc.hadron.api.Permissions;
import dev.iredsc.hadron.api.plugin.ServicePriority;
import dev.iredsc.hadron.api.plugin.ServicesManager;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.server.level.ServerPlayer;

/** Permission nodes: declaring them, their defaults without a ranks plugin, and a ranks plugin deciding instead. */
public final class PermissionTests {
    private static final Node EVERYONE = Node.declare("selftest.perm.everyone", Default.EVERYONE, "Anyone");
    private static final Node OPS = Node.declare("selftest.perm.ops", Default.OPS, "Operators");
    private static final Node NOBODY = Node.declare("selftest.perm.nobody", Default.NOBODY, "Nobody by default");

    @HadronTest
    @Covers(value = Node.class, method = "declare")
    @Covers(value = Node.class, method = "get")
    @Covers(value = Node.class, method = "all")
    void declaredNodesAreListedAndBadIdsAreRefusedWithTheFix(TestContext t) {
        t.assertTrue(Node.get("selftest.perm.ops") == OPS, "get should find the declared node");
        t.assertEquals(null, Node.get("selftest.perm.undeclared"), "get for a node nobody declared");
        t.assertTrue(Node.all().containsAll(List.of(EVERYONE, OPS, NOBODY)), "all should list the declared nodes");
        t.assertTrue(Node.declare("selftest.perm.ops", Default.OPS, "again") == OPS,
                "declaring the same node with the same default returns the first");
        try {
            Node.declare("NoDots", Default.OPS, "bad");
            t.fail("an id without a dot should be refused");
        } catch (IllegalArgumentException e) {
            t.assertTrue(e.getMessage().contains("plugin.thing"), "the refusal should show the shape, but said: "
                    + e.getMessage());
        }
        try {
            Node.declare("selftest.perm.ops", Default.NOBODY, "different default");
            t.fail("redeclaring a node with another default should be refused");
        } catch (IllegalArgumentException e) {
            t.assertTrue(e.getMessage().contains("declared twice"), "the refusal should say why, but said: "
                    + e.getMessage());
        }
    }

    @HadronTest
    @Covers(value = Node.class, method = "allows")
    @Covers(value = Permissions.class, method = "has")
    @Covers(value = Permissions.class, method = "fallback")
    @Covers(value = Permissions.class, method = "group")
    @Covers(value = Permissions.class, method = "inGroup")
    @Covers(value = Permissions.class, method = "prefix")
    @Covers(value = Permissions.class, method = "suffix")
    void withoutARanksPluginEveryNodeFallsBackToItsDefault(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        bob.setOp(true);
        ServerPlayer a = alice.player();
        ServerPlayer b = bob.player();
        t.assertTrue(Permissions.has(a, EVERYONE) && EVERYONE.allows(a), "everyone has an EVERYONE node");
        t.assertFalse(Permissions.has(a, OPS) || OPS.allows(a), "a non-operator lacks an OPS node");
        t.assertTrue(Permissions.has(b, OPS) && OPS.allows(b), "an operator has an OPS node");
        t.assertFalse(Permissions.has(b, NOBODY), "not even an operator has a NOBODY node by default");
        t.assertTrue(Permissions.fallback(b, OPS) && !Permissions.fallback(a, OPS), "fallback is the default alone");
        t.assertTrue(Permissions.has(t.server().createCommandSourceStack(), OPS), "the console has OPS nodes");
        t.assertFalse(Permissions.has(a.createCommandSourceStack(), OPS), "a player's command source is the player");
        t.assertEquals(Permissions.DEFAULT_GROUP, Permissions.group(a), "everyone's group without ranks");
        t.assertTrue(Permissions.inGroup(a, "default") && !Permissions.inGroup(a, "admin"), "the default group only");
        t.assertEquals("", Permissions.prefix(a), "no prefix without ranks");
        t.assertEquals("", Permissions.suffix(a), "no suffix without ranks");
    }

    /** A ranks plugin that grants alice the NOBODY node and denies her EVERYONE, and calls her an admin. */
    private static final class Ranks implements PermissionProvider {
        final List<String> asked = new CopyOnWriteArrayList<>();

        @Override
        public Boolean check(ServerPlayer player, String node) {
            asked.add(node);
            if (!player.getGameProfile().getName().equals("alice")) return null;
            if (node.equals(NOBODY.id)) return true;
            if (node.equals(EVERYONE.id)) return false;
            return null;
        }

        @Override
        public String group(ServerPlayer player) {
            return "admin";
        }

        @Override
        public boolean inGroup(ServerPlayer player, String group) {
            return group.equals("admin") || group.equals("default");
        }

        @Override
        public String prefix(ServerPlayer player) {
            return "[A] ";
        }

        @Override
        public String suffix(ServerPlayer player) {
            return " *";
        }
    }

    @HadronTest
    @Covers(value = PermissionProvider.class, method = "check")
    @Covers(value = PermissionProvider.class, method = "group")
    @Covers(value = PermissionProvider.class, method = "inGroup")
    @Covers(value = PermissionProvider.class, method = "prefix")
    @Covers(value = PermissionProvider.class, method = "suffix")
    @Covers(value = Permissions.class, method = "has")
    @Covers(value = Permissions.class, method = "group")
    @Covers(value = Permissions.class, method = "inGroup")
    @Covers(value = Permissions.class, method = "prefix")
    @Covers(value = Permissions.class, method = "suffix")
    void aRanksPluginDecidesWhatItAnswersAndDefaultsDecideTheRest(TestContext t) {
        TestPlayer alice = t.player("alice");
        ServerPlayer a = alice.player();
        ServicesManager services = t.plugin().getServer().getServicesManager();
        Ranks ranks = new Ranks();
        services.register(PermissionProvider.class, ranks, t.plugin(), ServicePriority.Highest);
        try {
            t.assertTrue(Permissions.has(a, NOBODY), "the ranks plugin grants alice the NOBODY node");
            t.assertFalse(Permissions.has(a, EVERYONE), "the ranks plugin denies alice the EVERYONE node");
            t.assertFalse(Permissions.has(a, OPS), "a node the ranks plugin doesn't decide falls back to its default");
            t.assertTrue(ranks.asked.containsAll(List.of(NOBODY.id, EVERYONE.id, OPS.id)),
                    "every check should ask the ranks plugin first, but it was asked " + ranks.asked);
            t.assertEquals("admin", Permissions.group(a), "the group from the ranks plugin");
            t.assertTrue(Permissions.inGroup(a, "admin"), "inGroup from the ranks plugin");
            t.assertEquals("[A] ", Permissions.prefix(a), "the prefix from the ranks plugin");
            t.assertEquals(" *", Permissions.suffix(a), "the suffix from the ranks plugin");
        } finally {
            services.unregister(ranks);
        }
        t.assertEquals("", Permissions.prefix(a), "no prefix once the ranks plugin is gone");
    }

    @HadronTest
    @Covers(value = Permissions.class, method = "refresh")
    void refreshResendsThePlayersCommands(TestContext t) {
        TestPlayer alice = t.player("alice");
        alice.clearReceived();
        Permissions.refresh(alice.player());
        t.tick(2);
        t.assertFalse(alice.received(ClientboundCommandsPacket.class).isEmpty(),
                "refresh should send alice a new command tree");
    }
}
