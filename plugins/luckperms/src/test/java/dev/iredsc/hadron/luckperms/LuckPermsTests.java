package dev.iredsc.hadron.luckperms;

import dev.iredsc.hadron.api.Default;
import dev.iredsc.hadron.api.Node;
import dev.iredsc.hadron.api.Permissions;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PermissionNode;
import net.luckperms.api.node.types.PrefixNode;
import net.luckperms.api.node.types.SuffixNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.luckperms.api.context.DefaultContextKeys;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.Commands;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.server.level.ServerPlayer;

/**
 * The provider against a real LuckPerms: what LuckPerms decides wins, what it doesn't falls back to Hadron's defaults.
 * LuckPerms keeps its data for the whole run, so each test uses players and groups no other test touches.
 */
public final class LuckPermsTests {
    private static final Node OPS = Node.declare("lptest.ops", Default.OPS, "Operators by default");
    private static final Node EVERYONE = Node.declare("lptest.everyone", Default.EVERYONE, "Everyone by default");
    private static final Node NOBODY = Node.declare("lptest.nobody", Default.NOBODY, "Nobody by default");

    private static LuckPerms luckPerms() {
        return LuckPermsProvider.get();
    }

    /** Changes a user and waits for LuckPerms to save it and recalculate their permissions. */
    private static void modify(TestContext t, UUID id, Consumer<User> change) {
        finish(t, "LuckPerms to save the user", luckPerms().getUserManager().modifyUser(id, change));
    }

    private static Group group(TestContext t, String name, Consumer<Group> change) {
        LuckPerms lp = luckPerms();
        Group group = finish(t, "LuckPerms to create " + name, lp.getGroupManager().createAndLoadGroup(name));
        change.accept(group);
        finish(t, "LuckPerms to save " + name, lp.getGroupManager().saveGroup(group));
        return group;
    }

    private static <T> T finish(TestContext t, String what, CompletableFuture<T> future) {
        t.awaitAsync(what, future::isDone, 200);
        return future.join();
    }

    @HadronTest
    void aNodeLuckPermsDecidesWinsAndOthersFallBack(TestContext t) {
        TestPlayer ann = t.player("lp_ann");
        ServerPlayer a = ann.player();
        t.awaitAsync("LuckPerms to load ann", () -> luckPerms().getUserManager().getUser(a.getUUID()) != null, 200);
        t.assertFalse(Permissions.has(a, OPS), "a node LuckPerms says nothing about falls back to its OPS default");
        t.assertTrue(Permissions.has(a, EVERYONE), "and an EVERYONE node to everyone");

        modify(t, a.getUUID(), u -> {
            u.data().add(PermissionNode.builder(OPS.id).value(true).build());
            u.data().add(PermissionNode.builder(EVERYONE.id).value(false).build());
        });
        t.awaitAsync("LuckPerms' grant to reach Hadron", () -> Permissions.has(a, OPS), 200);
        t.assertFalse(Permissions.has(a, EVERYONE), "LuckPerms denying a node beats its EVERYONE default");
        t.assertFalse(Permissions.has(a, NOBODY), "a node LuckPerms never mentions keeps its default");
    }

    @HadronTest
    void wildcardsFromLuckPermsCoverHadronsNodes(TestContext t) {
        TestPlayer wes = t.player("lp_wes");
        ServerPlayer w = wes.player();
        t.awaitAsync("LuckPerms to load wes", () -> luckPerms().getUserManager().getUser(w.getUUID()) != null, 200);
        modify(t, w.getUUID(), u -> u.data().add(PermissionNode.builder("lptest.*").value(true).build()));
        t.awaitAsync("the wildcard to reach Hadron", () -> Permissions.has(w, NOBODY), 200);
        t.assertTrue(Permissions.has(w, OPS), "lptest.* grants every lptest node");
    }

    @HadronTest
    void groupsPrefixesAndSuffixesComeFromLuckPerms(TestContext t) {
        group(t, "lp_helper", g -> g.data().add(SuffixNode.builder(" &7*", 10).build()));
        group(t, "lp_admin", g -> {
            g.data().add(InheritanceNode.builder("lp_helper").build());
            g.data().add(PrefixNode.builder("&c[Admin] ", 100).build());
            g.data().add(net.luckperms.api.node.types.WeightNode.builder(100).build());
        });
        TestPlayer gus = t.player("lp_gus");
        ServerPlayer g = gus.player();
        t.awaitAsync("LuckPerms to load gus", () -> luckPerms().getUserManager().getUser(g.getUUID()) != null, 200);
        t.assertEquals("default", Permissions.group(g), "a new player is in LuckPerms' default group");
        t.assertEquals("", Permissions.prefix(g), "and has no prefix");

        modify(t, g.getUUID(), u -> u.data().add(InheritanceNode.builder("lp_admin").build()));
        t.awaitAsync("gus to join lp_admin", () -> Permissions.inGroup(g, "lp_admin"), 200);
        t.assertEquals("lp_admin", Permissions.group(g), "the heaviest group is the primary one");
        t.assertTrue(Permissions.inGroup(g, "LP_HELPER"), "a group inherited through another counts, in any case");
        t.assertTrue(Permissions.inGroup(g, "default"), "everyone is in the default group");
        t.assertFalse(Permissions.inGroup(g, "lp_nobody"), "a group nobody gave gus");
        t.assertEquals("§c[Admin] ", Permissions.prefix(g), "the prefix, with & codes turned into colors");
        t.assertEquals(" §7*", Permissions.suffix(g), "the suffix inherited from lp_helper");
    }

    /** LuckPerms resends the list itself; this checks the integration still gets a new one to the player. */
    @HadronTest
    void aRankChangeResendsThePlayersCommands(TestContext t) {
        TestPlayer rae = t.player("lp_rae");
        ServerPlayer r = rae.player();
        t.awaitAsync("LuckPerms to load rae", () -> luckPerms().getUserManager().getUser(r.getUUID()) != null, 200);
        t.tick(2);
        rae.clearReceived();
        modify(t, r.getUUID(), u -> u.data().add(PermissionNode.builder("lptest.refresh").value(true).build()));
        t.awaitAsync("rae's client to get a new command tree",
                () -> !rae.received(ClientboundCommandsPacket.class).isEmpty(), 200);
    }

    /** A command granted only in one context reaches the player's client once they've joined in it. */
    @HadronTest
    void aCommandGrantedInAContextIsInTheTreeAfterJoining(TestContext t) {
        Node inSurvival = Node.declare("lptest.survivalcmd", Default.NOBODY, "Only granted in survival");
        t.server().getCommands().getDispatcher().register(Commands.literal("lptest_survival")
                .requires(source -> Permissions.has(source, inSurvival)).executes(c -> 1));
        // Offline mode: LuckPerms keys a player by the UUID their name gives, before they ever join.
        UUID id = UUID.nameUUIDFromBytes("OfflinePlayer:lp_cai".getBytes(StandardCharsets.UTF_8));
        modify(t, id, u -> u.data().add(PermissionNode.builder(inSurvival.id)
                .withContext(DefaultContextKeys.GAMEMODE_KEY, "survival").build()));

        TestPlayer cai = t.player("lp_cai");
        t.assertEquals(id, cai.uuid(), "the UUID LuckPerms was given for lp_cai");
        t.assertTrue(Permissions.has(cai.player(), inSurvival), "cai is in survival, so has the node");
        CommandBuildContext context = CommandBuildContext.simple(t.server().registryAccess(),
                t.server().getWorldData().enabledFeatures());
        t.awaitAsync("cai's newest command tree to hold /lptest_survival", () -> {
            List<ClientboundCommandsPacket> trees = cai.received(ClientboundCommandsPacket.class);
            return !trees.isEmpty() && trees.get(trees.size() - 1).getRoot(context).getChild("lptest_survival") != null;
        }, 100);
    }

    @HadronTest
    void thePluginNamesTheModWhoseApiItUses(TestContext t) {
        t.assertEquals(java.util.List.of("luckperms"), t.plugin().getDescription().getApiDepend(), "api-depend");
        t.assertEquals(null, t.plugin().getDescription().getLoader(), "a write-once plugin names no loader");
    }
}
