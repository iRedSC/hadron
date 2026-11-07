package dev.iredsc.hadron.selftest.platform;

import dev.iredsc.hadron.api.net.DisplayNames;
import dev.iredsc.hadron.api.net.PacketRegistration;
import dev.iredsc.hadron.api.net.Packets;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
}
