package dev.iredsc.hadron.selftest.players;

import static dev.iredsc.hadron.selftest.entities.Fixtures.assertPlayer;
import static dev.iredsc.hadron.selftest.entities.Fixtures.near;
import static dev.iredsc.hadron.selftest.entities.Fixtures.ready;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.player.PlayerFishEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * PlayerFishEvent's cast and reel-in. Bites and catches need open water and a random wait of up to 30 seconds, so
 * they're not covered here.
 */
public final class FishingTests {
    private static void aimAtTheFloor(TestPlayer alice) {
        alice.player().setYRot(0.0F);
        alice.player().setXRot(45.0F);
        alice.hold(new ItemStack(Items.FISHING_ROD));
    }

    @HadronTest
    @Covers(PlayerFishEvent.class)
    void castingIsTheAnglersAndCancelledLeavesNoBobber(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        aimAtTheFloor(alice);
        List<PlayerFishEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerFishEvent e) {
                events.add(e);
                e.setCancelled(true);
            }
        });
        alice.useItem();
        t.tick();
        t.assertEquals(1, events.size(), "the number of PlayerFishEvents for a cast");
        t.assertEquals(PlayerFishEvent.State.FISHING, events.get(0).getState(), "the state");
        t.assertEquals(alice.player(), events.get(0).getPlayer(), "the angler");
        assertPlayer(t, events.get(0).getActor(), alice, "Casting");
        t.assertEquals(0, near(t, FishingHook.class).size(), "the number of bobbers");
        t.assertEquals(null, alice.player().fishing, "alice's line");
        // The next click casts again, rather than reeling in a bobber that was never in the world.
        events.clear();
        alice.useItem();
        t.assertEquals(1, events.size(), "the number of PlayerFishEvents for the second click");
        t.assertEquals(PlayerFishEvent.State.FISHING, events.get(0).getState(), "the second click's state");
    }

    @HadronTest
    @Covers(PlayerFishEvent.class)
    void reelingInFiresAndCancelledLeavesTheBobberOutAndTheRodUnworn(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        aimAtTheFloor(alice);
        alice.useItem();
        t.await("the bobber to land", () -> alice.player().fishing != null && alice.player().fishing.onGround(), 60);
        FishingHook hook = alice.player().fishing;
        List<PlayerFishEvent.State> states = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerFishEvent e) {
                states.add(e.getState());
                e.setCancelled(true);
            }
        });
        alice.useItem();
        t.assertEquals(1, states.size(), "the number of PlayerFishEvents for a reel-in");
        t.assertTrue(states.get(0) == PlayerFishEvent.State.IN_GROUND || states.get(0) == PlayerFishEvent.State.REEL_IN,
                "the state should be IN_GROUND or REEL_IN, but was " + states.get(0));
        t.assertFalse(hook.isRemoved(), "the bobber should still be out");
        t.assertEquals(hook, alice.player().fishing, "alice's line");
        t.assertEquals(0, alice.player().getMainHandItem().getDamageValue(), "the rod's damage");
    }
}
