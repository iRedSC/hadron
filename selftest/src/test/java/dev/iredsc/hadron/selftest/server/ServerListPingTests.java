package dev.iredsc.hadron.selftest.server;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.server.ServerListPingEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.network.protocol.status.ServerboundStatusRequestPacket;
import net.minecraft.server.network.ServerStatusPacketListenerImpl;

/**
 * The server list's ping: what a client's multiplayer screen shows. The harness has no status client, so the test
 * hands the server's own status handler the request packet over a connection it can read the answer from.
 */
public final class ServerListPingTests {
    /** What the server answers one status request with. */
    private static ServerStatus ping(TestContext t) {
        ServerStatus status = t.server().getStatus();
        t.assertTrue(status != null, "the test server should answer status requests");
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        try {
            new ServerStatusPacketListenerImpl(status, connection).handleStatusRequest(
                    ServerboundStatusRequestPacket.INSTANCE);
            channel.runPendingTasks();
            Object sent = channel.readOutbound();
            t.assertTrue(sent instanceof ClientboundStatusResponsePacket, "the server should answer with a status "
                    + "response, but sent " + sent);
            return ((ClientboundStatusResponsePacket) sent).status();
        } finally {
            channel.close();
        }
    }

    @HadronTest
    @Covers(ServerListPingEvent.class)
    void aChangedMotdAndCountsAreWhatTheServerListShows(TestContext t) {
        t.player("alice");
        List<ServerListPingEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ServerListPingEvent e) {
                heard.add(e);
                e.setMotd(Component.literal("Closed for maintenance"));
                e.setMaxPlayers(5);
                e.setNumPlayers(e.getNumPlayers() + 2);
            }
        });
        ServerStatus answer = ping(t);
        t.assertEquals(1, heard.size(), "the ServerListPingEvents for one ping");
        ServerListPingEvent e = heard.get(0);
        // Vanilla anonymizes a player who hasn't allowed server listing, as the test client hasn't.
        t.assertEquals(1, e.getPlayerSample().size(), "the event's sample of the one player online");
        t.assertEquals("Closed for maintenance", answer.description().getString(), "the MOTD sent");
        t.assertEquals(5, answer.players().orElseThrow().max(), "the max players sent");
        t.assertEquals(3, answer.players().orElseThrow().online(), "the online count sent (alice, plus 2)");
    }

    @HadronTest
    @Covers(ServerListPingEvent.class)
    void anUnchangedPingIsTheServersOwnAnswer(TestContext t) {
        int[] heard = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ServerListPingEvent e) {
                heard[0]++;
            }
        });
        ServerStatus answer = ping(t);
        t.assertEquals(1, heard[0], "the ServerListPingEvents for one ping");
        t.assertEquals(t.server().getStatus().description(), answer.description(), "the MOTD sent");
    }
}
