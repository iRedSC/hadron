package dev.iredsc.hadron.selftest.entities;

import static dev.iredsc.hadron.selftest.entities.Fixtures.spawn;

import dev.iredsc.hadron.api.Hadron;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.entity.EntityMoveEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * A vehicle's move is observed after its tick, once every passenger is seated, and a refusal puts all of it back: the
 * vehicle and every rider, before any client is sent the move.
 */
public final class VehicleRefusalTests {
    /** A boat at pos(2, 0, 0) + 0.75 facing east that lunges two blocks east in its own tick when told to. */
    private static Boat lungingBoat(TestContext t, boolean[] lunge) {
        Fixtures.ready(t);
        Boat boat = new Boat(EntityType.BOAT, t.level()) {
            @Override
            public void tick() {
                super.tick();
                if (lunge[0]) {
                    lunge[0] = false;
                    setPos(getX() + 2, getY(), getZ());
                }
            }
        };
        BlockPos at = t.pos(2, 0, 0);
        boat.moveTo(at.getX() + 0.75, at.getY(), at.getZ() + 0.5, 90f, 0f);
        boat.setNoGravity(true);
        t.level().addFreshEntity(boat);
        return boat;
    }

    /** The boat lunges carrying alice and bob; only bob's arrival is refused, and the whole move goes back. */
    @HadronTest
    @Covers(EntityMoveEvent.class)
    void aRefusalForOneRiderPutsBackTheWholeMove(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        boolean[] lunge = {false};
        Boat boat = lungingBoat(t, lunge);
        t.assertTrue(alice.player().startRiding(boat, true) && bob.player().startRiding(boat, true),
                "alice and bob should get in the boat");
        t.tick(3);
        BlockPos boatWas = boat.blockPosition(), aliceWas = alice.player().blockPosition(),
                bobWas = bob.player().blockPosition();
        int boundary = t.pos(5, 0, 0).getX();
        int[] refused = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityMoveEvent e) {
                if (e.getEntity() == boat && bob.player().getX() >= boundary) {
                    refused[0]++;
                    e.setCancelled(true);
                }
            }
        });
        t.tick();
        lunge[0] = true;
        t.tick();
        t.assertTrue(refused[0] > 0, "bob's arrival should have been refused");
        t.assertEquals(List.of(boatWas, aliceWas, bobWas), List.of(boat.blockPosition(),
                alice.player().blockPosition(), bob.player().blockPosition()), "the boat, alice and bob, all put back");
    }

    /** A refused lunge leaves nobody moved, and a player watching never sees the boat move. */
    @HadronTest
    @Covers(EntityMoveEvent.class)
    void aRefusedMoveIsNeverSentToAnyClient(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer carol = t.player("carol");
        boolean[] lunge = {false};
        Boat boat = lungingBoat(t, lunge);
        t.assertTrue(alice.player().startRiding(boat, true), "alice should get in the boat");
        t.tick(5);
        Vec3 boatWas = boat.position(), aliceWas = alice.player().position();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityMoveEvent e) {
                if (e.getEntity() == boat) e.setCancelled(true);
            }
        });
        t.tick();
        carol.clearReceived();
        lunge[0] = true;
        t.tick(3);
        t.assertTrue(boat.position().distanceTo(boatWas) < 1.0E-6, "the boat should be exactly where it was");
        t.assertTrue(alice.player().position().distanceTo(aliceWas) < 1.0E-6, "alice should be exactly where she was");
        t.assertEquals(0L, VehiclePacketTests.movesShown(t, carol, boat), "the packets showing carol the boat moved");
    }

    /** A turn made before the first tick anyone listens is still measured from where the boat faced before it. */
    @HadronTest
    @Covers(EntityMoveEvent.class)
    void theFirstMoveAfterAListenerArrivesCanBeRefused(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        BlockPos at = t.pos(2, 0, 0);
        Boat boat = spawn(t, EntityType.BOAT, at);
        boat.moveTo(at.getX() + 0.75, at.getY(), at.getZ() + 0.5, -90f, 0f);
        t.assertTrue(alice.player().startRiding(boat, true) && bob.player().startRiding(boat, true),
                "alice and bob should get in the boat");
        t.tick(3);
        BlockPos bobWas = bob.player().blockPosition();
        int[] refused = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityMoveEvent e) {
                if (e.getEntity() == boat && bob.player().getX() >= at.getX() + 1) {
                    refused[0]++;
                    e.setCancelled(true);
                }
            }
        });
        boat.setYRot(90f);
        t.tick(3);
        t.assertTrue(refused[0] > 0, "the turn should have been refused");
        t.assertEquals(bobWas, bob.player().blockPosition(), "bob's block");
        t.assertEquals(-90f, boat.getYRot(), "the boat's facing, put back");
    }

    /** After a rearing horse's moves were refused, its rider gets off, and on again, and rides along normally. */
    @HadronTest
    @Covers(EntityMoveEvent.class)
    void aRiderRefusedBeforeGetsBackOnAndRidesAlong(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos at = t.pos(2, 0, 0);
        Horse horse = spawn(t, EntityType.HORSE, at);
        horse.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.25, 0f, 0f);
        horse.setTamed(true);
        horse.equipSaddle(new ItemStack(Items.SADDLE), null);
        t.assertTrue(alice.player().startRiding(horse, true), "alice should get on the horse");
        t.tick(3);
        int[] refused = {0};
        Listener refuse = new Listener() {
            @EventHandler
            void on(EntityMoveEvent e) {
                if (e.getEntity() == horse && alice.player().getZ() < at.getZ()) {
                    refused[0]++;
                    e.setCancelled(true);
                }
            }
        };
        t.registerEvents(refuse);
        horse.setStanding(true);
        t.tick(10);
        t.assertTrue(refused[0] > 0, "the horse rearing should have been refused");
        alice.player().stopRiding();
        Hadron.getPluginManager().unregisterEvents(refuse);
        t.tick(3);
        t.assertTrue(alice.player().startRiding(horse, true), "alice should get back on");
        horse.setPos(horse.getX() + 3, horse.getY(), horse.getZ());
        t.tick(3);
        Vec3 seat = horse.getPassengerRidingPosition(alice.player())
                .subtract(alice.player().getVehicleAttachmentPoint(horse));
        t.assertTrue(alice.player().position().distanceTo(seat) < 0.01, "alice should sit on the horse, at " + seat
                + ", but is at " + alice.player().position());
    }
}
