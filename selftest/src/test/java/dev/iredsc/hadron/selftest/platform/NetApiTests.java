package dev.iredsc.hadron.selftest.platform;

import dev.iredsc.hadron.api.net.DisplayNames;
import dev.iredsc.hadron.api.net.PacketEvent;
import dev.iredsc.hadron.api.net.PacketHandler;
import dev.iredsc.hadron.api.net.PacketRegistration;
import dev.iredsc.hadron.api.net.Packets;
import dev.iredsc.hadron.api.net.Visibility;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;

/** The rest of the packet and visibility API: showing to all, reading and resetting names, and handler threads. */
public final class NetApiTests {
    @HadronTest
    @Covers(value = Visibility.class, method = "showToAll")
    @Covers(value = Visibility.class, method = "hideFromAll")
    @Covers(value = Visibility.class, method = "hidePlayer")
    @Covers(value = Visibility.class, method = "isHiddenFromAll")
    void showToAllUndoesEveryHideThePluginMade(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        TestPlayer carol = t.player("carol");
        Visibility.hideFromAll(t.plugin(), alice.player());
        Visibility.hidePlayer(t.plugin(), carol.player(), alice.player());
        t.tick();
        t.assertFalse(bob.isShown(alice.player()) || carol.isShown(alice.player()), "alice should be hidden first");
        Visibility.showToAll(t.plugin(), alice.player());
        t.tick(2);
        t.assertFalse(Visibility.isHiddenFromAll(t.plugin(), alice.player()), "isHiddenFromAll after showToAll");
        t.assertTrue(bob.isShown(alice.player()), "showToAll should send alice to bob again");
        t.assertTrue(carol.isShown(alice.player()), "showToAll should undo the per-viewer hide from carol too");
        t.assertInTabList(bob, alice.player());
    }

    @HadronTest
    @Covers(value = DisplayNames.class, method = "getDisplayName")
    @Covers(value = DisplayNames.class, method = "setDisplayName")
    @Covers(value = DisplayNames.class, method = "reset")
    void resetPutsBackThePlayersOwnName(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        t.assertEquals("alice", DisplayNames.getDisplayName(alice.player()).getString(), "alice's own name");
        DisplayNames.setDisplayName(t.plugin(), alice.player(), Component.literal("Captain"));
        t.assertEquals("Captain", DisplayNames.getDisplayName(alice.player()).getString(), "the name once set");
        DisplayNames.reset(t.plugin(), alice.player());
        t.assertEquals("alice", DisplayNames.getDisplayName(alice.player()).getString(), "the name after reset");
        bob.clearReceived();
        alice.chat("back to normal");
        t.assertChat(bob, "<alice> back to normal");
    }

    @HadronTest
    @Covers(value = PacketEvent.class, method = "isCancelled")
    @Covers(value = PacketEvent.class, method = "setCancelled")
    @Covers(value = PacketEvent.class, method = "runOnServerThread")
    @Covers(value = PacketHandler.class, method = "handle")
    @Covers(value = Packets.class, method = "onSend")
    @Covers(value = Packets.class, method = "onReceive")
    void aLaterHandlerCanUncancelAndServerWorkHopsThreads(TestContext t) {
        TestPlayer bob = t.player("bob");
        PacketRegistration cancel = Packets.onSend(t.plugin(), ClientboundSystemChatPacket.class, e -> {
            if (e.getPacket().content().getString().equals("rescued")) e.setCancelled(true);
        });
        List<Boolean> sawCancelled = new CopyOnWriteArrayList<>();
        PacketRegistration rescue = Packets.onSend(t.plugin(), ClientboundSystemChatPacket.class, e -> {
            if (!e.getPacket().content().getString().equals("rescued")) return;
            sawCancelled.add(e.isCancelled());
            e.setCancelled(false);
        });
        List<Boolean> onServerThread = new CopyOnWriteArrayList<>();
        PacketRegistration hop = Packets.onReceive(t.plugin(), ServerboundSetCarriedItemPacket.class,
                e -> e.runOnServerThread(() -> onServerThread.add(t.server().isSameThread())));
        try {
            bob.player().sendSystemMessage(Component.literal("rescued"));
            t.tick();
            t.assertEquals(List.of(true), sawCancelled, "whether the second handler saw the packet cancelled");
            t.assertChat(bob, "rescued");
            bob.selectSlot(2);
            t.await("the handler's server-thread work to run", () -> !onServerThread.isEmpty(), 10);
            t.assertEquals(List.of(true), onServerThread, "where runOnServerThread ran the work");
        } finally {
            cancel.unregister();
            rescue.unregister();
            hop.unregister();
        }
    }
}
