package dev.iredsc.hadron.selftest.players;

import static dev.iredsc.hadron.selftest.entities.Fixtures.center;
import static dev.iredsc.hadron.selftest.entities.Fixtures.console;
import static dev.iredsc.hadron.selftest.entities.Fixtures.near;
import static dev.iredsc.hadron.selftest.entities.Fixtures.ready;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.player.PlayerDeathEvent;
import dev.iredsc.hadron.api.event.player.PlayerRespawnEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** PlayerDeathEvent and PlayerRespawnEvent: the death line, what drops and what's kept, where they come back. */
public final class DeathTests {
    /**
     * The respawn button: the harness has no action for it, so this hands the server the packet a client sends.
     * Respawning makes a new ServerPlayer, which {@code TestPlayer.player()} doesn't follow; this returns it.
     */
    static ServerPlayer respawn(TestContext t, TestPlayer player) {
        player.player().connection.handleClientCommand(
                new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        t.tick(2);
        ServerPlayer now = t.server().getPlayerList().getPlayer(player.uuid());
        if (now == null) throw new AssertionError(player.name() + " is not online after respawning");
        return now;
    }

    private static int dropped(TestContext t, Item item) {
        int count = 0;
        for (ItemEntity entity : near(t, ItemEntity.class)) {
            if (entity.getItem().is(item)) count += entity.getItem().getCount();
        }
        return count;
    }

    @HadronTest
    @Covers(PlayerDeathEvent.class)
    void aChangedDeathLineIsWhatOthersSee(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        List<PlayerDeathEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerDeathEvent e) {
                if (e.getPlayer() != bob.player()) return;
                events.add(e);
                e.setDeathMessage(Component.literal("bob tripped over a cable"));
            }
        });
        console(t, "kill bob");
        t.tick();
        t.assertEquals(1, events.size(), "the number of PlayerDeathEvents");
        t.assertChat(alice, "bob tripped over a cable");
        t.assertNoChat(alice, "bob was killed");
    }

    @HadronTest
    @Covers(PlayerDeathEvent.class)
    void deathDropsAreTheListenersChoiceAndKeptItemsComeBack(TestContext t) {
        TestPlayer bob = t.player("bob");
        bob.give(new ItemStack(Items.DIRT, 3));
        bob.give(new ItemStack(Items.DIAMOND, 1));
        ready(t); // the drops must land where they can be found
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerDeathEvent e) {
                if (e.getPlayer() != bob.player()) return;
                for (ItemStack stack : List.copyOf(e.getDrops())) {
                    if (stack.is(Items.DIAMOND)) {
                        e.getDrops().remove(stack);
                        e.getItemsToKeep().add(stack);
                    }
                }
                e.getDrops().add(new ItemStack(Items.EMERALD));
                e.setDroppedExp(0);
            }
        });
        console(t, "kill bob");
        t.tick();
        t.assertEquals(3, dropped(t, Items.DIRT), "the dirt dropped");
        t.assertEquals(1, dropped(t, Items.EMERALD), "the emerald the listener added");
        t.assertEquals(0, dropped(t, Items.DIAMOND), "the diamonds dropped (the listener kept them)");
        ServerPlayer back = respawn(t, bob);
        t.assertEquals(1, back.getInventory().countItem(Items.DIAMOND), "the diamonds bob has after respawning");
        t.assertEquals(0, back.getInventory().countItem(Items.DIRT), "the dirt bob has after respawning");
    }

    @HadronTest
    @Covers(PlayerRespawnEvent.class)
    void aChangedRespawnPointIsWhereTheyRespawn(TestContext t) {
        TestPlayer bob = t.player("bob");
        Vec3 there = center(t.pos(4, 0, -3));
        int[] asked = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerRespawnEvent e) {
                if (!e.getPlayer().getUUID().equals(bob.uuid())) return;
                asked[0]++;
                e.setRespawnLocation(t.level(), there);
            }
        });
        console(t, "kill bob");
        ServerPlayer back = respawn(t, bob);
        t.assertEquals(1, asked[0], "the number of PlayerRespawnEvents");
        t.assertEquals(t.level(), back.serverLevel(), "the world bob respawned in");
        t.assertTrue(back.position().distanceTo(there) < 0.01, "bob should respawn at " + there + ", but is at "
                + back.position());
    }
}
