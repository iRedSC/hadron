package dev.iredsc.hadron.selftest.harness;

import dev.iredsc.hadron.api.net.PacketRegistration;
import dev.iredsc.hadron.api.net.Packets;
import dev.iredsc.hadron.checks.Nondeterministic;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import io.netty.channel.Channel;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;

/**
 * The harness itself: an action returns only once its effects have arrived. If it can't tell they have (work that
 * never stops coming, a network thread that never answers), the action fails rather than return early, since a
 * negative assertion (assertNoChat, assertNotSent) right after it would otherwise pass with the effect still on its
 * way.
 */
public final class SettleTests {
    @HadronTest
    void anActionFailsWhenWorkKeepsArrivingWithoutATick(TestContext t) {
        TestPlayer alice = t.player("alice");
        MinecraftServer server = t.server();
        // A task that queues itself again, far past what settling between two ticks will run, then sends a line.
        int[] left = {15_000_000};
        Runnable requeue = new Runnable() {
            @Override
            public void run() {
                if (--left[0] > 0) server.tell(new TickTask(server.getTickCount(), this));
                else alice.player().sendSystemMessage(Component.literal("late"));
            }
        };
        server.tell(new TickTask(server.getTickCount(), requeue));
        RuntimeException failure = null;
        try {
            alice.chat("hello");
        } catch (RuntimeException e) {
            failure = e;
        } finally {
            left[0] = 1;
        }
        if (failure == null) {
            t.fail("chat returned while the server still had work queued (alice has " + alice.chat()
                    + "), so an assertNoChat(alice, \"late\") here would pass early; it should fail instead");
        }
        t.assertTrue(failure.getMessage() != null && failure.getMessage().contains("re-queues itself"),
                "the failure should say what keeps work arriving, but was: " + failure.getMessage());
    }

    @HadronTest
    void anActionFailsWhenChunkPacketsKeepArriving(TestContext t) {
        TestPlayer alice = t.player("alice");
        // A listener that answers every chunk acknowledgement with another batch: only chunk packets, never ending.
        boolean[] on = {true};
        PacketRegistration loop = Packets.onReceive(t.plugin(), ServerboundChunkBatchReceivedPacket.class, e -> {
            e.setCancelled(true);
            if (on[0]) e.getPlayer().connection.send(new ClientboundChunkBatchFinishedPacket(0));
        });
        RuntimeException failure = null;
        try {
            alice.player().connection.send(new ClientboundChunkBatchFinishedPacket(0));
            alice.selectSlot(2);
        } catch (RuntimeException e) {
            failure = e;
        } finally {
            on[0] = false;
            loop.unregister();
        }
        if (failure == null) t.fail("an action returned while chunk packets were still arriving, so a negative "
                + "assertion right after it could pass early; it should fail instead");
        t.assertTrue(failure.getMessage() != null && failure.getMessage().contains("answers every packet"),
                "the failure should say what keeps work arriving, but was: " + failure.getMessage());
    }

    /** Run on a network thread: holds it past the 10 s the harness waits for one. */
    @Nondeterministic(reason = "stands in for a network thread that never answers, which the harness bounds by wall time")
    private static void hangPastTheHarnessWait() {
        try {
            Thread.sleep(10_500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @HadronTest
    void anActionFailsWhenANetworkThreadNeverAnswers(TestContext t) throws ReflectiveOperationException {
        TestPlayer alice = t.player("alice");
        List<Channel> channels = new ArrayList<>();
        List<Connection> connections = t.server().getConnection().getConnections();
        // Vanilla keeps a connection's channel private (the accessor is the loader's), so the test reads the field.
        Field field = Connection.class.getDeclaredField("channel");
        field.setAccessible(true);
        synchronized (connections) {
            for (Connection c : connections) if (field.get(c) instanceof Channel ch) channels.add(ch);
        }
        t.assertTrue(!channels.isEmpty(), "alice's connection should have a channel");
        // Longer than the harness waits for a network thread.
        channels.get(0).eventLoop().execute(SettleTests::hangPastTheHarnessWait);
        RuntimeException failure = null;
        try {
            alice.chat("hello");
        } catch (RuntimeException e) {
            failure = e;
        }
        if (failure == null) {
            t.fail("chat returned though a network thread hadn't answered, so what it carried may still be on its way");
        }
        t.assertTrue(failure.getMessage() != null && failure.getMessage().contains("network thread"),
                "the failure should say a network thread didn't answer, but was: " + failure.getMessage());
    }

    @HadronTest
    void aLongTeleportSettlesWhereThePlayerWent(TestContext t) {
        TestPlayer alice = t.player("alice");
        net.minecraft.server.level.ServerPlayer player = alice.player();
        double x = player.getX() + 40, y = player.getY(), z = player.getZ();
        // Farther than the server lets a client report moving in one tick, and acted on before the next tick: the
        // client's answer is checked against where alice started the tick, refused, and she is teleported back. A
        // client that answered each correction at once, between ticks, would loop with the server forever.
        player.teleportTo(player.serverLevel(), x, y, z, player.getYRot(), player.getXRot());
        alice.chat("arrived");
        t.assertChat(alice, "arrived");
        t.tick(2);
        t.assertTrue(player.distanceToSqr(x, y, z) < 1, "alice should be where she was teleported, but is at "
                + player.position());
    }
}
