package dev.iredsc.hadron.selftest.entities;

import static dev.iredsc.hadron.selftest.entities.Fixtures.spawn;

import dev.iredsc.hadron.api.event.Carried;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.event.entity.EntityMoveEvent;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent;
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
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.Vec3;

/**
 * What a teleport carries: each ride's destination is a bound built through the passenger tree before it goes, and,
 * within a world, where riders really land is checked once they have, the whole teleport put back on a refusal.
 */
public final class TeleportCarryTests {
    /** The single destination area a teleport of {@code root} states for its rides, heard while it fires. */
    private static List<Place.Spot> rideBounds(TestContext t, Entity root) {
        List<Place.Spot> bounds = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityTeleportEvent e) {
                if (e.getEntity() != root || !(e.getCarried() instanceof Carried.Known k)) return;
                for (Carried.Ride ride : k.rides()) if (ride.to() instanceof Place.Spot s) bounds.add(s);
            }
        });
        return bounds;
    }

    /** Four boats stacked, each with a pig, all just mounted: every rider lands inside what the teleport stated. */
    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void aTowerOfFreshlyMountedBoatsLandsInsideItsBound(TestContext t) {
        t.player("alice");
        BlockPos source = t.pos(2, 0, 0);
        Boat root = spawn(t, EntityType.BOAT, source);
        Boat vehicle = root;
        List<Entity> riders = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Pig pig = spawn(t, EntityType.PIG, source);
            Boat next = spawn(t, EntityType.BOAT, source);
            t.assertTrue(pig.startRiding(vehicle, true) && next.startRiding(vehicle, true), "the tower should stack");
            riders.add(pig);
            riders.add(next);
            vehicle = next;
        }
        List<Place.Spot> bounds = rideBounds(t, root);
        BlockPos at = t.pos(5, 0, 0);
        root.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        t.assertEquals(riders.size(), bounds.size(), "the rides the teleport named");
        for (Entity rider : riders) {
            t.assertTrue(bounds.get(0).box().isInside(rider.blockPosition()), "the stated bound " + bounds.get(0)
                    + " should hold " + rider.getName().getString() + " at " + rider.position());
        }
    }

    /** A player just got in a boat sits below its top; the teleport's bound still holds them. */
    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void aFreshBoatRidersLowSeatIsInsideTheBound(TestContext t) {
        TestPlayer alice = t.player("alice");
        Boat boat = spawn(t, EntityType.BOAT, t.pos(2, 0, 0));
        t.assertTrue(alice.player().startRiding(boat, true), "alice should get in the boat");
        List<Place.Spot> bounds = rideBounds(t, boat);
        BlockPos to = t.pos(5, 0, 0);
        boat.teleportTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
        t.assertEquals(1, bounds.size(), "the teleport's one ride");
        t.assertTrue(bounds.get(0).box().isInside(alice.player().blockPosition()), "the bound " + bounds.get(0)
                + " should hold alice at " + alice.player().position());
    }

    /**
     * A vehicle that seats its rider five blocks off once it is somewhere else (as a mod's might), which no bound
     * foresees: once it lands, the rider's block is checked, and refusing that puts the whole teleport back.
     */
    @HadronTest
    @Covers(EntityTeleportEvent.class)
    @Covers(EntityMoveEvent.class)
    void aRiderLandingOutsideWhatTheTeleportSaidIsAskedAgain(TestContext t) {
        t.player("alice");
        Fixtures.ready(t);
        BlockPos at = t.pos(2, 0, 0);
        double far = at.getZ() + 2;
        Pig pig = new Pig(EntityType.PIG, t.level()) {
            @Override
            protected void positionRider(Entity rider, Entity.MoveFunction move) {
                // On its back here; five blocks east once past z + 2, where nothing before the teleport could tell.
                if (getZ() < far) super.positionRider(rider, move);
                else if (hasPassenger(rider)) move.accept(rider, getX() + 5, getY(), getZ());
            }
        };
        pig.setNoAi(true);
        pig.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        t.level().addFreshEntity(pig);
        Chicken chicken = spawn(t, EntityType.CHICKEN, at);
        t.assertTrue(chicken.startRiding(pig, true), "the chicken should get on the pig");
        t.tick(2);
        Vec3 pigWas = pig.position(), chickenWas = chicken.position();
        List<EntityMoveEvent> refused = new ArrayList<>();
        List<Carried.Ride> landed = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void allow(EntityTeleportEvent e) {
                // A protection plugin hears teleports too; this one lets the teleport itself through.
            }

            @EventHandler
            void on(EntityMoveEvent e) {
                if (e.getEntity() != pig) return;
                refused.add(e);
                if (e.getCarried() instanceof Carried.Known k) landed.addAll(k.rides());
                e.setCancelled(true);
            }
        });
        BlockPos to = t.pos(2, 0, 4);
        pig.teleportTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
        t.assertEquals(1, refused.size(), "the check on where the chicken landed");
        t.assertTrue(landed.size() == 1 && landed.get(0).to().equals(Place.Spot.at(t.level(), to.east(5))),
                "the check should state where the chicken really landed, " + to.east(5) + ": " + landed);
        t.assertTrue(pig.position().distanceTo(pigWas) < 1.0E-6, "the pig should be back at " + pigWas + ", but is at "
                + pig.position());
        t.assertTrue(chicken.position().distanceTo(chickenWas) < 1.0E-6, "the chicken should be back at " + chickenWas
                + ", but is at " + chicken.position());
    }
}
