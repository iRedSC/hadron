package dev.iredsc.hadron.selftest.players;

import static dev.iredsc.hadron.selftest.entities.Fixtures.console;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.player.PlayerJoinEvent;
import dev.iredsc.hadron.api.event.player.PlayerKickEvent;
import dev.iredsc.hadron.api.event.player.PlayerLoginEvent;
import dev.iredsc.hadron.api.event.player.PlayerQuitEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import net.minecraft.network.chat.Component;

/** Logging in, joining, quitting and being kicked: refusals hold, and the lines others see are the event's. */
public final class ConnectionTests {
    @HadronTest
    @Covers(PlayerLoginEvent.class)
    void aDeniedLoginKeepsThePlayerOutWithTheReason(TestContext t) {
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerLoginEvent e) {
                if (e.getPlayer().getGameProfile().getName().equals("mallory")) {
                    e.disallow(Component.literal("No entry today"));
                }
            }
        });
        try {
            t.player("mallory");
        } catch (AssertionError refused) {
            t.assertTrue(String.valueOf(refused.getMessage()).contains("No entry today"), "the refusal should give "
                    + "the listener's reason, but said: " + refused.getMessage());
            t.assertEquals(null, t.server().getPlayerList().getPlayerByName("mallory"), "mallory in the player list");
            return;
        }
        t.fail("mallory should have been refused at login, but joined");
    }

    @HadronTest
    @Covers(PlayerJoinEvent.class)
    void aChangedJoinLineIsWhatOthersSee(TestContext t) {
        TestPlayer bob = t.player("bob");
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerJoinEvent e) {
                if (e.getPlayer().getGameProfile().getName().equals("alice")) {
                    e.setJoinMessage(Component.literal("alice arrives by balloon"));
                }
            }
        });
        t.player("alice");
        t.tick();
        t.assertChat(bob, "alice arrives by balloon");
        t.assertNoChat(bob, "alice joined the game");
    }

    @HadronTest
    @Covers(PlayerJoinEvent.class)
    void aRemovedJoinLineIsNeverSent(TestContext t) {
        TestPlayer bob = t.player("bob");
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerJoinEvent e) {
                if (e.getPlayer().getGameProfile().getName().equals("alice")) e.setJoinMessage(null);
            }
        });
        t.player("alice");
        t.tick();
        t.assertNoChat(bob, "alice joined");
    }

    @HadronTest
    @Covers(PlayerJoinEvent.class)
    void anUnchangedJoinLineIsVanillasOnce(TestContext t) {
        TestPlayer bob = t.player("bob");
        int[] heard = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerJoinEvent e) {
                heard[0]++;
            }
        });
        t.player("alice");
        t.tick();
        t.assertEquals(1, heard[0], "the number of PlayerJoinEvents");
        long lines = bob.chat().stream().filter(l -> l.contains("alice joined the game")).count();
        t.assertEquals(1L, lines, "the number of \"alice joined the game\" lines bob saw");
    }

    @HadronTest
    @Covers(PlayerQuitEvent.class)
    void aChangedQuitLineIsWhatOthersSee(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerQuitEvent e) {
                if (e.getPlayer() == alice.player()) e.setQuitMessage(Component.literal("alice floats away"));
            }
        });
        alice.disconnect();
        t.tick();
        t.assertChat(bob, "alice floats away");
        t.assertNoChat(bob, "alice left the game");
    }

    @HadronTest
    @Covers(PlayerKickEvent.class)
    void aChangedKickReasonIsWhatTheKickedPlayerSees(TestContext t) {
        TestPlayer alice = t.player("alice");
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerKickEvent e) {
                if (e.getPlayer() == alice.player() && e.getReason().getString().contains("Bad manners")) {
                    e.setReason(Component.literal("Rewritten by a listener"));
                }
            }
        });
        console(t, "kick alice Bad manners");
        t.tick(2);
        t.assertFalse(alice.isConnected(), "alice should have been kicked");
        t.assertTrue(alice.disconnectReason().contains("Rewritten by a listener"), "alice should see the listener's "
                + "reason, but saw: " + alice.disconnectReason());
    }

    @HadronTest
    @Covers(PlayerKickEvent.class)
    void aCancelledKickKeepsThePlayerOnline(TestContext t) {
        TestPlayer alice = t.player("alice");
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerKickEvent e) {
                if (e.getReason().getString().contains("Bad manners")) e.setCancelled(true);
            }
        });
        console(t, "kick alice Bad manners");
        t.tick(2);
        t.assertTrue(alice.isConnected(), "alice should still be connected, but left: " + alice.disconnectReason());
        t.assertTrue(t.server().getPlayerList().getPlayer(alice.uuid()) != null, "alice should still be in the list");
    }

    /**
     * Vanilla rejects some packets on the connection's own thread (a bad keep-alive, illegal chat characters, a chat
     * out of order). The kick they cause still reaches listeners on the server thread, so a listener may touch the
     * world, and both a cancel and a pass-through hold.
     */
    @HadronTest
    @Covers(PlayerKickEvent.class)
    void aKickFromThePacketThreadReachesListenersOnTheServerThread(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        java.util.List<String> heard = new java.util.ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerKickEvent e) {
                heard.add(e.getPlayer().getGameProfile().getName() + (t.server().isSameThread() ? "" : " off-thread"));
                if (e.getPlayer() == alice.player()) e.setCancelled(true);
            }
        });
        alice.chat("hello §c");
        bob.chat("hello §c");
        t.tick(2);
        t.assertEquals(java.util.List.of("alice", "bob"), heard, "each kick should reach the listener once, on "
                + "the server thread");
        t.assertTrue(alice.isConnected(), "alice's kick was cancelled, but she left: " + alice.disconnectReason());
        t.assertFalse(bob.isConnected(), "bob's kick wasn't cancelled, so he should have left");
    }
}
