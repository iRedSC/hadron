package dev.iredsc.hadron.selftest.entities;

import static dev.iredsc.hadron.selftest.entities.Fixtures.spawn;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.event.entity.EntityDismountEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.SideEffects;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.phys.Vec3;

/**
 * {@code EntityDismountEvent.getTo()} is where the rider is really set down, from a boat, a minecart and a horse, and a
 * refused dismount leaves the rider on, where it was, posed as it was.
 */
public final class DismountTests {
    /** Every dismount of {@code who} heard from now on, each cancelled when {@code refuse}. */
    private static List<EntityDismountEvent> hear(TestContext t, Entity who, boolean refuse) {
        List<EntityDismountEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityDismountEvent e) {
                if (e.getEntity() != who) return;
                heard.add(e);
                if (refuse) e.setCancelled(true);
            }
        });
        return heard;
    }

    /** Sneaking, as the client says it: the rider gets off on its next tick. */
    private static void sneak(TestPlayer player) {
        player.player().connection.handlePlayerInput(new ServerboundPlayerInputPacket(0, 0, false, true));
    }

    /** Alice on a fresh {@code vehicle}, seated. */
    private static <V extends Entity> V riding(TestContext t, TestPlayer alice, Function<TestContext, V> vehicle) {
        V v = vehicle.apply(t);
        t.assertTrue(alice.player().startRiding(v, true), "alice should get on the " + v.getName().getString());
        t.tick(2);
        return v;
    }

    private static Entity boat(TestContext t) {
        return spawn(t, EntityType.BOAT, t.pos(2, 0, 0));
    }

    private static Entity minecart(TestContext t) {
        return spawn(t, EntityType.MINECART, t.pos(2, 0, 0));
    }

    private static Entity horse(TestContext t) {
        Horse horse = spawn(t, EntityType.HORSE, t.pos(2, 0, 0));
        horse.setTamed(true); // an untamed horse throws its rider off on its own
        return horse;
    }

    /** Alice sneaks off {@code vehicle}: one dismount, stating exactly where she then is, away from her seat. */
    private static void setsDownWhereStated(TestContext t, Function<TestContext, Entity> vehicle) {
        TestPlayer alice = t.player("alice");
        Entity v = riding(t, alice, vehicle);
        Vec3 seat = alice.player().position();
        List<EntityDismountEvent> heard = hear(t, alice.player(), false);
        sneak(alice);
        t.await("alice to get off", () -> !alice.player().isPassenger(), 10);
        t.assertEquals(1, heard.size(), "alice's dismounts");
        EntityDismountEvent e = heard.get(0);
        t.assertEquals(v, e.getDismounted(), "what she got off");
        t.assertTrue(e.getTo().distanceTo(seat) > 0.3, "getting off sets her down beside the "
                + v.getName().getString() + ", away from her seat " + seat + ", but the event said " + e.getTo());
        t.assertTrue(e.getTo().distanceTo(alice.player().position()) < 1.0E-6, "alice should be where the event said "
                + "she is set down, " + e.getTo() + ", but is at " + alice.player().position());
        t.assertTrue(e.getPlaces().contains(Place.Spot.at(t.level(), alice.player().blockPosition())),
                "the event's places should hold where she is set down: " + e.getPlaces());
    }

    /** Alice sneaks, refused: she stays on {@code vehicle}, where she was, posed as she was, and nothing else happens. */
    private static void refusedStaysOn(TestContext t, Function<TestContext, Entity> vehicle) {
        TestPlayer alice = t.player("alice");
        Entity v = riding(t, alice, vehicle);
        Vec3 seat = alice.player().position();
        Pose pose = alice.player().getPose();
        List<EntityDismountEvent> heard = hear(t, alice.player(), true);
        SideEffects effects = t.watchSideEffects();
        sneak(alice);
        t.tick(3);
        t.assertFalse(heard.isEmpty(), "sneaking should have asked to get off");
        t.assertEquals(v, alice.player().getVehicle(), "alice's vehicle");
        t.assertTrue(alice.player().position().distanceTo(seat) < 1.0E-6, "alice should still be in her seat " + seat
                + ", but is at " + alice.player().position());
        t.assertEquals(pose, alice.player().getPose(), "alice's pose");
        effects.assertNone("a refused dismount");
    }

    @HadronTest
    @Covers(EntityDismountEvent.class)
    void gettingOutOfABoatStatesWhereTheRiderIsSetDown(TestContext t) {
        setsDownWhereStated(t, DismountTests::boat);
    }

    @HadronTest
    @Covers(EntityDismountEvent.class)
    void aRefusedBoatDismountKeepsTheRiderInHerSeat(TestContext t) {
        refusedStaysOn(t, DismountTests::boat);
    }

    @HadronTest
    @Covers(EntityDismountEvent.class)
    void gettingOutOfAMinecartStatesWhereTheRiderIsSetDown(TestContext t) {
        setsDownWhereStated(t, DismountTests::minecart);
    }

    @HadronTest
    @Covers(EntityDismountEvent.class)
    void aRefusedMinecartDismountKeepsTheRiderInHerSeat(TestContext t) {
        refusedStaysOn(t, DismountTests::minecart);
    }

    @HadronTest
    @Covers(EntityDismountEvent.class)
    void gettingOffAHorseStatesWhereTheRiderIsSetDown(TestContext t) {
        setsDownWhereStated(t, DismountTests::horse);
    }

    @HadronTest
    @Covers(EntityDismountEvent.class)
    void aRefusedHorseDismountKeepsTheRiderInTheSaddle(TestContext t) {
        refusedStaysOn(t, DismountTests::horse);
    }

    /** A mod placing a riding player with {@code dismountTo}: that spot is stated, and refused, nothing moves. */
    @HadronTest
    @Covers(EntityDismountEvent.class)
    void dismountToStatesItsSpotAndARefusalMovesNothing(TestContext t) {
        TestPlayer alice = t.player("alice");
        Entity boat = riding(t, alice, DismountTests::boat);
        Vec3 seat = alice.player().position();
        Vec3 spot = Fixtures.center(t.pos(-3, 0, 4));
        List<EntityDismountEvent> refused = hear(t, alice.player(), true);
        alice.player().dismountTo(spot.x, spot.y, spot.z);
        t.assertEquals(1, refused.size(), "alice's dismounts");
        t.assertEquals(spot, refused.get(0).getTo(), "where dismountTo places her");
        t.assertEquals(boat, alice.player().getVehicle(), "alice's vehicle after a refusal");
        t.assertTrue(alice.player().position().distanceTo(seat) < 1.0E-6, "a refused dismountTo moves nothing, but "
                + "alice is at " + alice.player().position());
    }

    /** {@code removeVehicle} called directly takes a rider off where it is: nothing places it, and the event says so. */
    @HadronTest
    @Covers(EntityDismountEvent.class)
    void aRiderTakenOffDirectlyStaysWhereItIs(TestContext t) {
        TestPlayer alice = t.player("alice");
        riding(t, alice, DismountTests::boat);
        Vec3 seat = alice.player().position();
        List<EntityDismountEvent> heard = hear(t, alice.player(), false);
        alice.player().removeVehicle();
        t.assertEquals(1, heard.size(), "alice's dismounts");
        t.assertTrue(heard.get(0).getTo().distanceTo(seat) < 1.0E-6, "the event should say she stays at " + seat
                + ", but said " + heard.get(0).getTo());
        t.assertTrue(alice.player().position().distanceTo(seat) < 1.0E-6, "alice should stay where she was");
    }

    /**
     * A shulker taken off directly isn't placed, so it doesn't round to a block's centre either: the event says where
     * it really stays.
     */
    @HadronTest
    @Covers(EntityDismountEvent.class)
    void aShulkerTakenOffDirectlyStaysOffCentre(TestContext t) {
        t.player("alice");
        Entity boat = boat(t);
        var shulker = spawn(t, EntityType.SHULKER, t.pos(2, 0, 0));
        t.assertTrue(shulker.startRiding(boat, true), "the shulker should get in the boat");
        t.tick(2);
        Vec3 seat = shulker.position();
        List<EntityDismountEvent> heard = hear(t, shulker, false);
        shulker.removeVehicle();
        t.assertEquals(1, heard.size(), "the shulker's dismounts");
        t.assertTrue(heard.get(0).getTo().distanceTo(shulker.position()) < 1.0E-6, "the event should say where the "
                + "shulker is, " + shulker.position() + ", but said " + heard.get(0).getTo());
        t.assertTrue(shulker.position().distanceTo(seat) < 1.0E-6, "nothing should have moved the shulker");
    }

    /**
     * A boat removed under its rider sets her down by no search: she stays in her seat's column, no lower than the
     * boat, and can't refuse.
     */
    @HadronTest
    @Covers(EntityDismountEvent.class)
    void aBoatRemovedUnderItsRiderLeavesHerWhereSheSat(TestContext t) {
        TestPlayer alice = t.player("alice");
        Entity boat = riding(t, alice, DismountTests::boat);
        Vec3 seat = alice.player().position();
        Vec3 stays = new Vec3(seat.x, Math.max(seat.y, boat.getY()), seat.z);
        List<EntityDismountEvent> heard = hear(t, alice.player(), true);
        boat.kill();
        t.assertEquals(1, heard.size(), "alice's dismounts");
        EntityDismountEvent e = heard.get(0);
        t.assertTrue(e.isForced(), "getting off a boat that is gone is forced");
        t.assertFalse(alice.player().isPassenger(), "a forced dismount goes ahead though refused");
        t.assertTrue(alice.player().position().distanceTo(stays) < 1.0E-6, "alice should be left where she sat, "
                + stays + ", but is at " + alice.player().position());
        t.assertTrue(e.getTo().distanceTo(alice.player().position()) < 1.0E-6, "alice should be where the event said "
                + "she is set down, " + e.getTo() + ", but is at " + alice.player().position());
    }

    /**
     * A listener that moves the boat as the rider gets off changes nothing about where she is set down: she ends
     * exactly where the event said, not beside the boat where it now is.
     */
    @HadronTest
    @Covers(EntityDismountEvent.class)
    void aBoatMovedByAListenerStillSetsTheRiderDownWhereStated(TestContext t) {
        TestPlayer alice = t.player("alice");
        Entity boat = riding(t, alice, DismountTests::boat);
        Vec3 boatWas = boat.position();
        List<Vec3> stated = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityDismountEvent e) {
                if (e.getEntity() != alice.player()) return;
                stated.add(e.getTo());
                boat.setPos(boat.getX() + 4.0, boat.getY(), boat.getZ());
            }
        });
        sneak(alice);
        t.await("alice to get off", () -> !alice.player().isPassenger(), 10);
        t.assertEquals(1, stated.size(), "alice's dismounts");
        t.assertTrue(boat.position().distanceTo(boatWas) > 3.9, "the listener should have moved the boat from "
                + boatWas + ", but it is at " + boat.position());
        t.assertTrue(stated.get(0).distanceTo(alice.player().position()) < 1.0E-6, "alice should be where the event "
                + "said she is set down, " + stated.get(0) + ", but is at " + alice.player().position());
    }

    /** A mob getting out of a boat is set down beside it like a player, and the event says where. */
    @HadronTest
    @Covers(EntityDismountEvent.class)
    void aMobLeavingABoatStatesWhereItIsSetDown(TestContext t) {
        t.player("alice");
        Entity boat = boat(t);
        var zombie = spawn(t, EntityType.ZOMBIE, t.pos(2, 0, 0));
        t.assertTrue(zombie.startRiding(boat, true), "the zombie should get in the boat");
        t.tick(2);
        Vec3 seat = zombie.position();
        List<EntityDismountEvent> heard = hear(t, zombie, false);
        zombie.stopRiding();
        t.assertEquals(1, heard.size(), "the zombie's dismounts");
        EntityDismountEvent e = heard.get(0);
        t.assertFalse(e.isForced(), "a mob getting out of a boat that stays can be refused");
        t.assertFalse(zombie.isPassenger(), "the zombie should be out of the boat");
        t.assertTrue(e.getTo().distanceTo(seat) > 0.3, "getting out sets it down beside the boat, away from its seat "
                + seat + ", but the event said " + e.getTo());
        t.assertTrue(e.getTo().distanceTo(zombie.position()) < 1.0E-6, "the zombie should be where the event said it "
                + "is set down, " + e.getTo() + ", but is at " + zombie.position());
        t.assertTrue(e.getPlaces().contains(Place.Spot.at(t.level(), zombie.blockPosition())),
                "the event's places should hold where it is set down: " + e.getPlaces());
    }

    /** A shulker getting out of a boat is placed, so it rounds to a block's centre: the event states that centre. */
    @HadronTest
    @Covers(EntityDismountEvent.class)
    void aShulkerLeavingABoatStatesTheBlockCentreItIsSetDownAt(TestContext t) {
        t.player("alice");
        Entity boat = boat(t);
        var shulker = spawn(t, EntityType.SHULKER, t.pos(2, 0, 0));
        t.assertTrue(shulker.startRiding(boat, true), "the shulker should get in the boat");
        t.tick(2);
        List<EntityDismountEvent> heard = hear(t, shulker, false);
        shulker.stopRiding();
        t.assertEquals(1, heard.size(), "the shulker's dismounts");
        EntityDismountEvent e = heard.get(0);
        t.assertFalse(e.isForced(), "a shulker getting out of a boat that stays can be refused");
        t.assertFalse(shulker.isPassenger(), "the shulker should be out of the boat");
        Vec3 at = shulker.position();
        t.assertEquals(Vec3.atBottomCenterOf(shulker.blockPosition()), at, "a shulker off its boat sits at the "
                + "bottom centre of its block");
        t.assertTrue(e.getTo().distanceTo(at) < 1.0E-6, "the shulker should be where the event said it is set down, "
                + e.getTo() + ", but is at " + at);
    }
}
