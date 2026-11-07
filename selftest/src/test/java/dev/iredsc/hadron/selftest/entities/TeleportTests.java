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
import net.minecraft.server.level.ServerLevel;
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

    /** A spot in the overworld's spawn chunks, which stay loaded, high enough to be clear of the terrain. */
    private static Vec3 overworldSpot(TestContext t) {
        BlockPos spawn = t.server().overworld().getSharedSpawnPos();
        return new Vec3(spawn.getX() + 0.5, 300, spawn.getZ() + 0.5);
    }

    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void aVehicleRedirectedToAnotherWorldTakesItsPassengerAlong(TestContext t) {
        t.player("alice");
        Pig pig = spawn(t, EntityType.PIG, t.pos(2, 0, 0));
        Chicken chicken = spawn(t, EntityType.CHICKEN, t.pos(2, 0, 0));
        t.assertTrue(chicken.startRiding(pig, true), "the chicken should get on the pig");
        ServerLevel overworld = t.server().overworld();
        Vec3 instead = overworldSpot(t);
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityTeleportEvent e) {
                if (e.getEntity() == pig) e.setTo(overworld, instead);
            }
        });
        console(t, "tp " + pig.getStringUUID() + " " + coords(center(t.pos(5, 0, -5))));
        t.tick();
        Entity arrived = overworld.getEntity(pig.getUUID());
        try {
            t.assertTrue(arrived instanceof Pig, "the pig should be in the overworld, but found " + arrived);
            assertAt(t, instead, arrived, "the pig");
            t.assertEquals(1, arrived.getPassengers().size(), "the pig's passengers in the overworld");
            Entity rider = arrived.getPassengers().get(0);
            t.assertEquals(chicken.getUUID(), rider.getUUID(), "the passenger should be the chicken");
            t.assertTrue(t.level().getEntity(chicken.getUUID()) == null, "the chicken shouldn't be left behind");
        } finally {
            if (arrived != null) {
                for (Entity e : List.copyOf(arrived.getPassengers())) e.discard();
                arrived.discard();
            }
            Entity rider = overworld.getEntity(chicken.getUUID());
            if (rider != null) rider.discard();
        }
    }

    /**
     * While one entity changes world, a listener there that teleports someone else starts a teleport of its own, which
     * is asked about like any other.
     */
    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void aTeleportStartedDuringAnothersWorldChangeIsAskedAbout(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        ready(t);
        Vec3 bobTo = center(t.pos(3, 0, 3));
        List<Entity> asked = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityTeleportEvent e) {
                asked.add(e.getEntity());
                if (e.getEntity() == bob.player()) e.setCancelled(true);
            }

            @EventHandler
            void on(dev.iredsc.hadron.api.event.player.PlayerChangedWorldEvent e) {
                if (e.getPlayer() == alice.player()) bob.player().teleportTo(bobTo.x, bobTo.y, bobTo.z);
            }
        });
        Vec3 bobFrom = bob.player().position();
        Vec3 spot = overworldSpot(t);
        alice.player().teleportTo(t.server().overworld(), spot.x, spot.y, spot.z, 0.0F, 0.0F);
        t.tick();
        t.assertEquals(List.of(alice.player(), bob.player()), asked, "the entities asked about");
        assertAt(t, bobFrom, bob.player(), "bob, whose teleport was refused,");
    }

    /**
     * An enderman's random teleport tries a spot and steps back when it doesn't fit there. That is one attempt: the
     * step back is never asked about, so refusing other teleports can't strand it at a spot it doesn't fit.
     */
    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void anEndermansFailedRandomTeleportStepsBackWithoutAnotherEvent(TestContext t) throws Exception {
        t.player("alice");
        var enderman = spawn(t, EntityType.ENDERMAN, t.pos(-3, 0, -3));
        Vec3 from = enderman.position();
        BlockPos target = t.pos(4, 0, 4);
        t.level().setBlockAndUpdate(target.above(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        List<TeleportCause> causes = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityTeleportEvent e) {
                if (e.getEntity() != enderman) return;
                causes.add(e.getCause());
                if (e.getCause() != TeleportCause.RANDOM_TELEPORT) e.setCancelled(true);
            }
        });
        // EnderMan.teleport(x, y, z) is private; it is what its AI and its dodging call.
        var teleport = net.minecraft.world.entity.monster.EnderMan.class.getDeclaredMethod("teleport", double.class,
                double.class, double.class);
        teleport.setAccessible(true);
        Object moved = teleport.invoke(enderman, target.getX() + 0.5, (double) target.getY(), target.getZ() + 0.5);
        t.assertEquals(false, moved, "whether the enderman found room at the target");
        t.assertEquals(List.of(TeleportCause.RANDOM_TELEPORT), causes, "the EntityTeleportEvents for one attempt");
        assertAt(t, from, enderman, "the enderman");
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
