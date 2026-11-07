package dev.iredsc.hadron.selftest.entities;

import static dev.iredsc.hadron.selftest.entities.Fixtures.assertPlayer;
import static dev.iredsc.hadron.selftest.entities.Fixtures.ready;
import static dev.iredsc.hadron.selftest.entities.Fixtures.assertServer;
import static dev.iredsc.hadron.selftest.entities.Fixtures.center;
import static dev.iredsc.hadron.selftest.entities.Fixtures.console;
import static dev.iredsc.hadron.selftest.entities.Fixtures.spawn;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent.TeleportCause;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** EntityTeleportEvent from /tp and ender pearls: the actor and cause, a refusal stays put, a redirect lands. */
public final class TeleportTests {
    private static String coords(Vec3 v) {
        return v.x + " " + v.y + " " + v.z;
    }

    private static void assertAt(TestContext t, Vec3 expected, Entity entity, String who) {
        t.assertTrue(entity.position().distanceTo(expected) < 0.01, who + " should be at " + expected + ", but is at "
                + entity.position());
    }

    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void aConsoleTeleportIsTheServersCommand(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        Vec3 to = center(t.pos(4, 0, 3));
        List<EntityTeleportEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityTeleportEvent e) {
                if (e.getEntity() == alice.player()) events.add(e);
            }
        });
        console(t, "tp alice " + coords(to));
        t.tick();
        t.assertEquals(1, events.size(), "the number of EntityTeleportEvents for one /tp");
        EntityTeleportEvent e = events.get(0);
        assertServer(t, e.getActor(), "A console /tp");
        t.assertEquals(TeleportCause.COMMAND, e.getCause(), "the cause");
        t.assertEquals(to, e.getTo(), "the destination asked about");
        t.assertEquals(t.level(), e.getToLevel(), "the destination world");
        assertAt(t, to, alice.player(), "alice");
    }

    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void aCancelledTeleportLeavesThePlayerWhereTheyWere(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        Vec3 from = alice.player().position();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityTeleportEvent e) {
                if (e.getEntity() == alice.player()) e.setCancelled(true);
            }
        });
        console(t, "tp alice " + coords(center(t.pos(4, 0, 3))));
        t.tick(2);
        assertAt(t, from, alice.player(), "alice");
    }

    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void redirectedTeleportLandsAtTheNewDestination(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        Vec3 instead = center(t.pos(-5, 0, 6));
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityTeleportEvent e) {
                if (e.getEntity() == alice.player()) e.setTo(t.level(), instead);
            }
        });
        console(t, "tp alice " + coords(center(t.pos(4, 0, 3))));
        t.tick(2);
        assertAt(t, instead, alice.player(), "alice");
    }

    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void aRedirectedVehicleTakesItsPassengerAlong(TestContext t) {
        t.player("alice"); // entities only tick, and commands only find them, near a player
        Pig pig = spawn(t, EntityType.PIG, t.pos(2, 0, 0));
        Chicken chicken = spawn(t, EntityType.CHICKEN, t.pos(2, 0, 0));
        t.assertTrue(chicken.startRiding(pig, true), "the chicken should get on the pig");
        Vec3 instead = center(t.pos(-4, 0, 4));
        List<List<Entity>> passengers = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityTeleportEvent e) {
                if (e.getEntity() != pig) return;
                passengers.add(e.getPassengers());
                e.setTo(t.level(), instead);
            }
        });
        console(t, "tp " + pig.getStringUUID() + " " + coords(center(t.pos(5, 0, -5))));
        t.tick();
        t.assertEquals(1, passengers.size(), "the number of EntityTeleportEvents for the pig");
        t.assertTrue(passengers.get(0).contains(chicken), "the event should list the chicken as travelling with the "
                + "pig, but listed " + passengers.get(0));
        assertAt(t, instead, pig, "the pig");
        t.assertEquals(pig, chicken.getVehicle(), "the chicken's vehicle");
        t.assertTrue(chicken.position().subtract(instead).horizontalDistance() < 0.5, "the chicken should have come along to "
                + instead + ", but is at " + chicken.position());
    }

    /** Alice throws a pearl ahead of her, down at the floor. */
    private static void throwPearl(TestPlayer alice) {
        alice.player().setYRot(0.0F); // facing +z, over the floor
        alice.player().setXRot(40.0F);
        alice.hold(new ItemStack(Items.ENDER_PEARL, 2));
        alice.useItem();
    }

    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void anEnderPearlTeleportIsTheThrowers(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        Vec3 from = alice.player().position();
        List<EntityTeleportEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityTeleportEvent e) {
                if (e.getEntity() == alice.player()) events.add(e);
            }
        });
        throwPearl(alice);
        t.await("the pearl to land", () -> !events.isEmpty(), 60);
        t.tick();
        t.assertEquals(1, events.size(), "the number of EntityTeleportEvents for one pearl");
        assertPlayer(t, events.get(0).getActor(), alice, "A pearl's teleport");
        t.assertEquals(TeleportCause.ENDER_PEARL, events.get(0).getCause(), "the cause");
        t.assertTrue(alice.player().position().distanceTo(from) > 1, "alice should have moved to where the pearl "
                + "landed, but is still at " + alice.player().position());
    }

    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void aCancelledPearlLeavesTheThrowerInPlaceAndUnhurt(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        Vec3 from = alice.player().position();
        int[] asked = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityTeleportEvent e) {
                if (e.getEntity() != alice.player()) return;
                asked[0]++;
                e.setCancelled(true);
            }
        });
        throwPearl(alice);
        t.await("the pearl to land", () -> asked[0] > 0, 60);
        t.tick(2);
        assertAt(t, from, alice.player(), "alice");
        t.assertEquals(alice.player().getMaxHealth(), alice.player().getHealth(), "alice's health (no pearl damage)");
    }

    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void aRedirectedPearlLandsAtTheNewDestination(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        BlockPos target = t.pos(-6, 0, -6);
        Vec3 instead = center(target);
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityTeleportEvent e) {
                if (e.getEntity() == alice.player()) e.setTo(t.level(), instead);
            }
        });
        throwPearl(alice);
        t.await("alice to arrive", () -> alice.player().position().distanceTo(instead) < 0.01, 60);
    }
}
