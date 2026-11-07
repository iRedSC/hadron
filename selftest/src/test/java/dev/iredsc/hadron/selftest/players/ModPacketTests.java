package dev.iredsc.hadron.selftest.players;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.player.PlayerModPacketEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestModPacket;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * A mod's network message from a modded client, through the test mod's {@code hadron:test_ping} channel: the event
 * comes first, on the server thread, and a cancel keeps the mod from ever handling it.
 */
public final class ModPacketTests {
    /** What a listener saw, when it saw it. */
    private record Seen(ServerPlayer player, ResourceLocation id, CustomPacketPayload payload, boolean serverThread,
            int handledBefore) {}

    private static List<Seen> watch(TestContext t, boolean cancel) {
        List<Seen> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerModPacketEvent e) {
                seen.add(new Seen(e.getPlayer(), e.getPayloadId(), e.getPayload(), t.server().isSameThread(),
                        t.modPacketsHandled().size()));
                if (cancel) e.setCancelled(true);
            }
        });
        return seen;
    }

    @HadronTest
    @Covers(PlayerModPacketEvent.class)
    void firesWithThePlayerAndPayloadOnTheServerThreadBeforeTheMod(TestContext t) {
        TestPlayer alice = t.moddedPlayer("alice");
        List<Seen> seen = watch(t, false);
        alice.sendModPacket(new TestModPacket("ping"));

        t.assertEquals(1, seen.size(), "PlayerModPacketEvents for one mod packet");
        Seen e = seen.get(0);
        t.assertTrue(e.player() == alice.player(), "the event's player should be alice, but was " + e.player());
        t.assertEquals(TestModPacket.TYPE.id(), e.id(), "the event's payload id");
        t.assertEquals(new TestModPacket("ping"), e.payload(), "the event's payload");
        t.assertTrue(e.serverThread(), "PlayerModPacketEvent should fire on the server thread");
        t.assertEquals(0, e.handledBefore(), "mod packets the mod had handled when the event fired");
        List<TestModPacket.Handled> handled = t.modPacketsHandled();
        t.assertEquals(1, handled.size(), "mod packets the mod handled after the event");
        t.assertTrue(handled.get(0).player() == alice.player(), "the mod should have handled alice's packet, "
                + "but got " + handled.get(0).player());
        t.assertEquals("ping", handled.get(0).packet().text(), "the text the mod received");
    }

    @HadronTest
    @Covers(PlayerModPacketEvent.class)
    void aCancelledModPacketNeverReachesTheMod(TestContext t) {
        TestPlayer alice = t.moddedPlayer("alice");
        List<Seen> seen = watch(t, true);
        alice.sendModPacket(new TestModPacket("refused"));
        t.tick(2);

        t.assertEquals(1, seen.size(), "PlayerModPacketEvents for one mod packet");
        t.assertEquals(List.of(), t.modPacketsHandled(), "mod packets the mod handled after a cancel");
        t.assertTrue(alice.isConnected(), "a cancelled mod packet shouldn't disconnect alice, but: "
                + alice.disconnectReason());
    }

    @HadronTest
    @Covers(PlayerModPacketEvent.class)
    void anUncancelledModPacketReachesTheModOnce(TestContext t) {
        TestPlayer alice = t.moddedPlayer("alice");
        List<Seen> seen = watch(t, false);
        alice.sendModPacket(new TestModPacket("one"));
        alice.sendModPacket(new TestModPacket("two"));
        t.tick(2);

        t.assertEquals(2, seen.size(), "PlayerModPacketEvents for two mod packets");
        List<String> texts = t.modPacketsHandled().stream().map(h -> h.packet().text()).toList();
        t.assertEquals(List.of("one", "two"), texts, "what the mod handled, in order");
    }
}
