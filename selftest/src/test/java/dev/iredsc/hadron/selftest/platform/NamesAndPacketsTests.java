package dev.iredsc.hadron.selftest.platform;

import dev.iredsc.hadron.api.event.EventPriority;
import dev.iredsc.hadron.api.net.DisplayNames;
import dev.iredsc.hadron.api.net.PacketEvent;
import dev.iredsc.hadron.api.net.PacketHandler;
import dev.iredsc.hadron.api.net.PacketRegistration;
import dev.iredsc.hadron.api.net.Packets;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.api.plugin.PluginManager;
import dev.iredsc.hadron.test.TestPlayer;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
/** Nicknames as other players' clients receive them, and plugins' packet listeners. */
public final class NamesAndPacketsTests {
    /** Every name the viewer's client was given for that player in tab list entries that add them. */
    private static List<String> profileNames(TestPlayer viewer, UUID player) {
        return viewer.received(ClientboundPlayerInfoUpdatePacket.class).stream()
                .filter(p -> p.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER))
                .flatMap(p -> p.entries().stream())
                .filter(e -> e.profileId().equals(player) && e.profile() != null)
                .map(e -> e.profile().getName())
                .toList();
    }

    @HadronTest
    @Covers(value = DisplayNames.class, method = "setDisplayName")
    void aDisplayNameIsTheNameInChat(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        try {
            DisplayNames.setDisplayName(t.plugin(), alice.player(), Component.literal("Captain"));
            alice.chat("ahoy");
            t.assertChat(bob, "Captain");
            t.assertChat(bob, "ahoy");
            t.assertNoChat(bob, "<alice>");
        } finally {
            DisplayNames.reset(t.plugin(), alice.player());
        }
    }

    @HadronTest
    @Covers(value = DisplayNames.class, method = "setPlayerListName")
    void aPlayerListNameReachesOtherClientsTabLists(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        try {
            bob.clearReceived();
            DisplayNames.setPlayerListName(t.plugin(), alice.player(), Component.literal("[Admin] alice"));
            t.tick();
            boolean got = bob.received(ClientboundPlayerInfoUpdatePacket.class).stream()
                    .filter(p -> p.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME))
                    .flatMap(p -> p.entries().stream())
                    .anyMatch(e -> e.profileId().equals(alice.uuid()) && e.displayName() != null
                            && e.displayName().getString().equals("[Admin] alice"));
            t.assertTrue(got, "bob's client should get alice's tab list name \"[Admin] alice\"");
        } finally {
            DisplayNames.reset(t.plugin(), alice.player());
        }
    }

    @HadronTest
    @Covers(value = DisplayNames.class, method = "setNameTag")
    void aNameTagRenamesTheProfileOtherViewersReceive(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        try {
            alice.clearReceived();
            bob.clearReceived();
            DisplayNames.setNameTag(t.plugin(), alice.player(), "Cap");
            t.tick(3);
            List<String> bobSees = profileNames(bob, alice.uuid());
            t.assertTrue(!bobSees.isEmpty() && bobSees.get(bobSees.size() - 1).equals("Cap"), "bob's client should "
                    + "be given alice's profile named Cap, but got " + bobSees);
            t.assertTrue(profileNames(alice, alice.uuid()).stream().allMatch("alice"::equals), "alice should keep "
                    + "her own real name, but got " + profileNames(alice, alice.uuid()));
        } finally {
            DisplayNames.reset(t.plugin(), alice.player());
        }
    }

    @HadronTest
    @Covers(value = Packets.class, method = "onSend")
    @Covers(value = PacketHandler.class, method = "handle")
    @Covers(value = PacketEvent.class, method = "getPacket")
    @Covers(value = PacketEvent.class, method = "setCancelled")
    void aCancelledOutgoingPacketNeverArrives(TestContext t) {
        TestPlayer bob = t.player("bob");
        PacketRegistration reg = Packets.onSend(t.plugin(), ClientboundSystemChatPacket.class, e -> {
            if (e.getPacket().content().getString().contains("secret")) e.setCancelled(true);
        });
        try {
            bob.player().sendSystemMessage(Component.literal("the secret is 42"));
            bob.player().sendSystemMessage(Component.literal("public news"));
            t.tick();
            t.assertNoChat(bob, "secret");
            t.assertChat(bob, "public news");
        } finally {
            reg.unregister();
        }
    }

    @HadronTest
    @Covers(value = Packets.class, method = "onSend")
    @Covers(value = PacketHandler.class, method = "handle")
    @Covers(value = PacketEvent.class, method = "getPacket")
    @Covers(value = PacketEvent.class, method = "setPacket")
    void aReplacedOutgoingPacketArrivesInstead(TestContext t) {
        TestPlayer bob = t.player("bob");
        PacketRegistration reg = Packets.onSend(t.plugin(), ClientboundSystemChatPacket.class, e -> {
            if (e.getPacket().content().getString().equals("original")) {
                e.setPacket(new ClientboundSystemChatPacket(Component.literal("replaced"), false));
            }
        });
        try {
            bob.player().sendSystemMessage(Component.literal("original"));
            t.tick();
            t.assertChat(bob, "replaced");
            t.assertNoChat(bob, "original");
        } finally {
            reg.unregister();
        }
    }

    @HadronTest
    @Covers(value = Packets.class, method = "onReceive")
    @Covers(value = PacketHandler.class, method = "handle")
    @Covers(value = PacketEvent.class, method = "getPacket")
    @Covers(value = PacketEvent.class, method = "getPlayer")
    @Covers(value = PacketEvent.class, method = "isOutgoing")
    void anIncomingListenerSeesWhatTheClientSent(TestContext t) {
        TestPlayer bob = t.player("bob");
        List<Integer> slots = new CopyOnWriteArrayList<>();
        List<String> who = new CopyOnWriteArrayList<>();
        PacketRegistration reg = Packets.onReceive(t.plugin(), ServerboundSetCarriedItemPacket.class, e -> {
            slots.add(e.getPacket().getSlot());
            who.add(e.getPlayer().getGameProfile().getName() + (e.isOutgoing() ? " (outgoing)" : ""));
        });
        try {
            bob.selectSlot(3);
            t.await("the listener to see bob's slot change", () -> slots.contains(3), 5);
            t.assertEquals(List.of("bob"), who, "the players the listener saw");
        } finally {
            reg.unregister();
        }
    }

    @HadronTest
    @Covers(value = Packets.class, method = "onSend")
    @Covers(value = PacketEvent.class, method = "setCancelled")
    @Covers(value = PacketRegistration.class, method = "unregister")
    void anUnregisteredPacketListenerStopsActing(TestContext t) {
        TestPlayer bob = t.player("bob");
        PacketRegistration reg = Packets.onSend(t.plugin(), ClientboundSystemChatPacket.class,
                e -> e.setCancelled(true));
        bob.player().sendSystemMessage(Component.literal("first"));
        t.tick();
        t.assertNoChat(bob, "first");
        reg.unregister();
        bob.player().sendSystemMessage(Component.literal("second"));
        t.tick();
        t.assertChat(bob, "second");
    }

    /** The members of a team as the viewer's client has them, replaying every team packet it received. */
    private static Set<String> teamOnClient(TestPlayer viewer, String team) {
        Set<String> members = new LinkedHashSet<>();
        for (ClientboundSetPlayerTeamPacket p : viewer.received(ClientboundSetPlayerTeamPacket.class)) {
            if (!p.getName().equals(team)) continue;
            if (p.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.REMOVE) {
                members.clear();
            } else if (p.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.ADD) {
                members.clear();
                members.addAll(p.getPlayers());
            } else if (p.getPlayerAction() == ClientboundSetPlayerTeamPacket.Action.ADD) {
                members.addAll(p.getPlayers());
            } else if (p.getPlayerAction() == ClientboundSetPlayerTeamPacket.Action.REMOVE) {
                members.removeAll(p.getPlayers());
            }
        }
        return members;
    }

    @HadronTest
    @Covers(value = DisplayNames.class, method = "setNameTag")
    @Covers(value = DisplayNames.class, method = "reset")
    void aNameTagReplacesTheTeamMemberOtherClientsSeeAndGivesItBack(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        Scoreboard scoreboard = t.server().getScoreboard();
        PlayerTeam team = scoreboard.addPlayerTeam("selftest_red");
        try {
            scoreboard.addPlayerToTeam("alice", team);
            t.tick();
            t.assertEquals(Set.of("alice"), teamOnClient(bob, team.getName()), "the team on bob's client");
            DisplayNames.setNameTag(t.plugin(), alice.player(), "Cap");
            t.tick(2);
            t.assertEquals(Set.of("Cap"), teamOnClient(bob, team.getName()), "the team on bob's client once alice "
                    + "is tagged Cap");
            DisplayNames.reset(t.plugin(), alice.player());
            t.tick(2);
            t.assertEquals(Set.of("alice"), teamOnClient(bob, team.getName()), "the team on bob's client once "
                    + "alice's tag is reset");
            scoreboard.removePlayerFromTeam("alice", team);
            t.tick();
            t.assertEquals(Set.of(), teamOnClient(bob, team.getName()), "the team on bob's client once alice left");
        } finally {
            DisplayNames.reset(t.plugin(), alice.player());
            scoreboard.removePlayerTeam(team);
        }
    }

    @HadronTest
    @Covers(value = PacketEvent.class, method = "setPacket")
    @Covers(value = Packets.class, method = "onReceive")
    void replacingAPacketWithAnotherKindIsRefusedSoLaterHandlersStillRun(TestContext t) {
        TestPlayer bob = t.player("bob");
        List<String> refusals = new CopyOnWriteArrayList<>();
        List<Double> checked = new CopyOnWriteArrayList<>();
        PacketRegistration swap = Packets.onReceive(t.plugin(), ServerboundMovePlayerPacket.Pos.class,
                EventPriority.LOWEST, e -> {
                    ServerboundMovePlayerPacket.Pos pos = e.getPacket();
                    try {
                        ((PacketEvent) e).setPacket(new ServerboundMovePlayerPacket.PosRot(pos.getX(0), pos.getY(0),
                                pos.getZ(0), 0, 0, pos.isOnGround()));
                    } catch (IllegalArgumentException refused) {
                        refusals.add(refused.getMessage());
                    }
                });
        PacketRegistration check = Packets.onReceive(t.plugin(), ServerboundMovePlayerPacket.Pos.class,
                e -> checked.add(e.getPacket().getX(0)));
        try {
            BlockPos at = t.pos(1, 1, 0);
            bob.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            t.await("the later handler to check bob's move", () -> !checked.isEmpty(), 10);
            t.assertTrue(!refusals.isEmpty() && refusals.get(0).contains("player.connection"), "replacing a Pos with "
                    + "a PosRot should be refused with the fix, but refusals were " + refusals);
        } finally {
            swap.unregister();
            check.unregister();
        }
    }

    private static void writeProbeJar(File jar, String minecraft) throws IOException {
        String yml = "name: SelfTestPacketProbe\nversion: \"1\"\nminecraft: " + minecraft + "\nmain: "
                + PacketProbePlugin.class.getName() + "\n";
        String classFile = PacketProbePlugin.class.getName().replace('.', '/') + ".class";
        try (OutputStream file = Files.newOutputStream(jar.toPath()); ZipOutputStream zip = new ZipOutputStream(file)) {
            zip.putNextEntry(new ZipEntry("plugin.yml"));
            zip.write(yml.getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry(classFile));
            try (InputStream in = PacketProbePlugin.class.getClassLoader().getResourceAsStream(classFile)) {
                if (in == null) throw new IOException("the test jar has no " + classFile);
                in.transferTo(zip);
            }
        }
    }

    @HadronTest(timeout = 700)
    @Covers(value = Packets.class, method = "onSend")
    void aDisablingPluginsPacketHandlersStopBeforeItsOnDisable(TestContext t) throws Exception {
        TestPlayer bob = t.player("bob");
        List<Object> log = new CopyOnWriteArrayList<>();
        System.getProperties().put(PacketProbePlugin.LOG, log);
        PluginManager plugins = t.plugin().getServer().getPluginManager();
        File jar = new File(t.plugin().getServer().getPluginsFolder(), "selftest-packet-probe.jar");
        try {
            writeProbeJar(jar, t.server().getServerVersion());
            t.await("Hadron to enable the probe", () -> plugins.isPluginEnabled("SelfTestPacketProbe"), 300);
            bob.player().sendSystemMessage(Component.literal("probe hello"));
            t.tick();
            t.assertEquals(List.of("handled: probe hello"), log, "what the probe's handler saw while enabled");
            Files.delete(jar.toPath());
            t.await("Hadron to disable the probe", () -> !plugins.isPluginEnabled("SelfTestPacketProbe"), 300);
            t.tick();
            t.assertChat(bob, "probe goodbye");
            t.assertEquals(List.of("handled: probe hello"), log, "what the probe's handler saw; it must not run "
                    + "after the probe began disabling");
        } finally {
            Files.deleteIfExists(jar.toPath());
            System.getProperties().remove(PacketProbePlugin.LOG);
            File data = new File(t.plugin().getServer().getPluginsFolder(), "SelfTestPacketProbe");
            if (data.isDirectory()) data.delete();
        }
    }

    @HadronTest(timeout = 700)
    @Covers(value = Packets.class, method = "onSend")
    void aPacketHandlerAlreadyRunningFinishesBeforeItsPluginsOnDisable(TestContext t) throws Exception {
        TestPlayer bob = t.player("bob");
        List<Object> log = new CopyOnWriteArrayList<>();
        System.getProperties().put(PacketProbePlugin.LOG, log);
        PluginManager plugins = t.plugin().getServer().getPluginManager();
        File jar = new File(t.plugin().getServer().getPluginsFolder(), "selftest-packet-probe.jar");
        Thread sender = null;
        try {
            writeProbeJar(jar, t.server().getServerVersion());
            t.await("Hadron to enable the probe", () -> plugins.isPluginEnabled("SelfTestPacketProbe"), 300);
            // Sending off the server thread runs the handler there, as packets on a network thread do.
            sender = new Thread(() -> bob.player().connection.send(
                    new ClientboundSystemChatPacket(Component.literal("probe slow"), false)), "selftest-slow-send");
            sender.start();
            long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!log.contains("slow started") && System.nanoTime() < until) Thread.onSpinWait();
            t.assertTrue(log.contains("slow started"), "the probe's handler started on the sending thread");
            Files.delete(jar.toPath());
            t.await("Hadron to disable the probe", () -> !plugins.isPluginEnabled("SelfTestPacketProbe"), 300);
            sender.join(5000);
            t.assertEquals(List.of("slow started", "slow finished before onDisable"), log,
                    "a handler call already running when the probe began disabling must finish before its onDisable");
        } finally {
            if (sender != null) sender.join(5000);
            Files.deleteIfExists(jar.toPath());
            System.getProperties().remove(PacketProbePlugin.LOG);
            File data = new File(t.plugin().getServer().getPluginsFolder(), "SelfTestPacketProbe");
            if (data.isDirectory()) data.delete();
        }
    }
}
