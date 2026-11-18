package dev.iredsc.hadron.selftest.entities;

import static dev.iredsc.hadron.selftest.entities.Fixtures.spawn;

import dev.iredsc.hadron.api.event.Carried;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.event.entity.EntityMountEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.SideEffects;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.phys.Vec3;

/**
 * {@code EntityMountEvent.getTo()} is where the rider is really seated, on each seat of a boat and a camel, a minecart
 * and a horse, with everything the rider carries and every rider already aboard that it shifts stated too, and a
 * refused mount leaves everyone where they were.
 */
public final class MountTests {
    /** Every mount of {@code who} heard from now on, each cancelled when {@code refuse}. */
    private static List<EntityMountEvent> hear(TestContext t, Entity who, boolean refuse) {
        List<EntityMountEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityMountEvent e) {
                if (e.getEntity() != who) return;
                heard.add(e);
                if (refuse) e.setCancelled(true);
            }
        });
        return heard;
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

    private static Entity camel(TestContext t) {
        return spawn(t, EntityType.CAMEL, t.pos(2, 0, 0));
    }

    /** {@code vehicle} with bob already on it, so whoever boards next takes its second seat. */
    private static Function<TestContext, Entity> withBob(Function<TestContext, Entity> vehicle) {
        return t -> {
            Entity v = vehicle.apply(t);
            t.assertTrue(t.player("bob").player().startRiding(v), "bob should get on the " + v.getName().getString());
            t.tick(2);
            return v;
        };
    }

    /** The one mount heard said exactly where {@code rider} now is, on {@code vehicle}, among its places. */
    private static EntityMountEvent assertSeatedWhereStated(TestContext t, List<EntityMountEvent> heard, Entity rider,
            Entity vehicle) {
        String who = rider.getName().getString();
        t.assertEquals(1, heard.size(), who + "'s mounts");
        EntityMountEvent e = heard.get(0);
        t.assertEquals(vehicle, e.getMount(), "what " + who + " mounts");
        t.assertEquals(vehicle, rider.getVehicle(), who + "'s vehicle");
        t.assertTrue(e.getTo().distanceTo(rider.position()) < 1.0E-6, who + " should be seated where the event said, "
                + e.getTo() + ", but is at " + rider.position() + " on the " + vehicle.getName().getString() + " at "
                + vehicle.position());
        t.assertTrue(e.getPlaces().contains(Place.Spot.at(t.level(), rider.blockPosition())),
                "the event's places should hold the block " + who + " is seated in: " + e.getPlaces());
        return e;
    }

    /**
     * {@code vehicle} with a zombie already on it, driving: a player who boards goes ahead of it, and it to the back.
     */
    private static Function<TestContext, Entity> withZombie(Function<TestContext, Entity> vehicle) {
        return t -> {
            Entity v = vehicle.apply(t);
            Zombie zombie = spawn(t, EntityType.ZOMBIE, v.blockPosition());
            t.assertTrue(zombie.startRiding(v, true), "the zombie should get on the " + v.getName().getString());
            t.tick(2);
            return v;
        };
    }

    /** Alice boards {@code v}: one mount, stating exactly where she then sits. */
    private static EntityMountEvent boards(TestContext t, Entity v) {
        TestPlayer alice = t.player("alice");
        Vec3 from = alice.player().position();
        List<EntityMountEvent> heard = hear(t, alice.player(), false);
        t.assertTrue(alice.player().startRiding(v), "alice should get on the " + v.getName().getString());
        EntityMountEvent e = assertSeatedWhereStated(t, heard, alice.player(), v);
        t.assertTrue(e.getPosition().distanceTo(from) < 1.0E-6, "the event should say she sets out from " + from);
        return e;
    }

    /**
     * Alice boards a fresh {@code vehicle}, moving nobody else: one mount, stating exactly where she then sits and
     * nobody besides, and whoever was aboard is exactly where they were.
     */
    private static EntityMountEvent seatsWhereStated(TestContext t, Function<TestContext, Entity> vehicle) {
        t.player("alice");
        Entity v = vehicle.apply(t);
        t.tick(2);
        List<Entity> riders = List.copyOf(v.getPassengers());
        List<Vec3> seats = riders.stream().map(Entity::position).toList();
        EntityMountEvent e = boards(t, v);
        t.assertEquals(Carried.none(), e.getCarried(), "who else alice's mount moves");
        t.assertEquals(seats, riders.stream().map(Entity::position).toList(), "where the riders already aboard sit");
        return e;
    }

    /**
     * {@code e} names {@code shifted}, already aboard, and nobody else: from the block it was in, {@code from}, to the
     * block it is in now, {@code by} blocks along from where it was, and that is where the vehicle keeps it.
     */
    private static void assertShiftStated(TestContext t, EntityMountEvent e, Entity shifted, Vec3 from, double by) {
        String who = shifted.getName().getString();
        t.assertTrue(e.getCarried() instanceof Carried.Known, "who the mount moves is known: " + e.getCarried());
        List<Carried.Ride> rides = ((Carried.Known) e.getCarried()).rides();
        t.assertEquals(List.of(shifted), rides.stream().map(Carried.Ride::entity).toList(), "who the mount moves");
        t.assertTrue(Math.abs(shifted.position().distanceTo(from) - by) < 1.0E-6, who + " should have been shifted "
                + by + " from " + from + " at once, but is at " + shifted.position());
        t.assertFalse(shifted.blockPosition().equals(BlockPos.containing(from)), "this shift should take " + who
                + " into another block than " + BlockPos.containing(from).toShortString());
        t.assertEquals(Place.Spot.at(t.level(), BlockPos.containing(from)), rides.get(0).from(), "where " + who
                + " sets out");
        t.assertEquals(Place.Spot.at(t.level(), shifted.blockPosition()), rides.get(0).to(), "where " + who
                + " ends up; it is at " + shifted.position());
        t.assertTrue(e.getPlaces().contains(Place.Spot.at(t.level(), shifted.blockPosition())),
                "the event's places should hold the block " + who + " is shifted into: " + e.getPlaces());
        Vec3 seat = shifted.position();
        Vec3 vehicleAt = e.getMount().position();
        t.tick(2);
        t.assertTrue(shifted.position().distanceTo(seat.add(e.getMount().position().subtract(vehicleAt))) < 1.0E-6,
                who + " was seated at once where vanilla seats it on its next tick, but moved from " + seat + " to "
                + shifted.position());
    }

    /** Alice boards, refused: she stays where she was, posed as she was, the vehicle's riders are as they were. */
    private static void refusedStaysOff(TestContext t, Function<TestContext, Entity> vehicle) {
        TestPlayer alice = t.player("alice");
        Entity v = vehicle.apply(t);
        t.tick(2);
        Vec3 from = alice.player().position();
        Pose pose = alice.player().getPose();
        List<Entity> riders = List.copyOf(v.getPassengers());
        List<Vec3> seats = riders.stream().map(Entity::position).toList();
        List<EntityMountEvent> heard = hear(t, alice.player(), true);
        SideEffects effects = t.watchSideEffects();
        t.assertFalse(alice.player().startRiding(v), "a refused mount should not seat alice");
        t.assertEquals(1, heard.size(), "alice's mounts");
        t.assertTrue(alice.player().getVehicle() == null, "alice should ride nothing, but rides "
                + alice.player().getVehicle());
        t.assertTrue(alice.player().position().equals(from), "alice should be exactly where she was, " + from
                + ", but is at " + alice.player().position());
        t.assertEquals(pose, alice.player().getPose(), "alice's pose");
        t.assertEquals(riders, v.getPassengers(), "the " + v.getName().getString() + "'s riders");
        t.assertEquals(seats, riders.stream().map(Entity::position).toList(), "where its riders sit");
        effects.assertNone("a refused mount");
        t.tick(2);
        t.assertTrue(alice.player().getVehicle() == null, "alice should still ride nothing two ticks later");
        t.assertEquals(riders, v.getPassengers(), "the " + v.getName().getString() + "'s riders two ticks later");
    }

    @HadronTest
    @Covers(EntityMountEvent.class)
    void aBoatsFirstSeatIsStated(TestContext t) {
        seatsWhereStated(t, MountTests::boat);
    }

    /**
     * The back seat lies behind the boat's own position, in another block here: the block a protection plugin would
     * never hear of from where the boat is.
     */
    @HadronTest
    @Covers(EntityMountEvent.class)
    void aBoatsSecondSeatIsStatedAndIsNotTheBoatsBlock(TestContext t) {
        t.player("alice");
        EntityMountEvent e = boards(t, withBob(MountTests::boat).apply(t));
        Entity boat = e.getMount();
        t.assertTrue(e.getTo().distanceTo(boat.position()) > 0.5, "the back seat is behind the boat at "
                + boat.position() + ", but the event said " + e.getTo());
        t.assertFalse(BlockPos.containing(e.getTo()).equals(boat.blockPosition()), "this boat's back seat is in "
                + "another block than the boat's " + boat.blockPosition().toShortString() + ", but the event said "
                + e.getTo());
    }

    /**
     * A boat's lone rider sits at its middle and moves 0.2 forward when a second boards: here across a block edge,
     * into a block nothing but the mount's carried riders would name.
     */
    @HadronTest
    @Covers(EntityMountEvent.class)
    void aSecondRiderStatesTheFirstRidersShift(TestContext t) {
        t.player("alice");
        TestPlayer bob = t.player("bob");
        Entity boat = boat(t);
        boat.setPos(boat.getX(), boat.getY(), boat.getBlockZ() + 0.9);
        t.assertTrue(bob.player().startRiding(boat), "bob should get in the boat");
        t.tick(2);
        Vec3 bobFrom = bob.player().position();
        EntityMountEvent e = boards(t, boat);
        assertShiftStated(t, e, bob.player(), bobFrom, 0.2);
    }

    /** A player boards ahead of a mob: the zombie driving goes from the boat's middle to its back seat, 0.6 behind. */
    @HadronTest
    @Covers(EntityMountEvent.class)
    void aPlayerBoardingABoatAMobDrivesStatesTheMobsMoveToTheBackSeat(TestContext t) {
        TestPlayer alice = t.player("alice");
        Entity boat = withZombie(MountTests::boat).apply(t);
        Entity zombie = boat.getPassengers().get(0);
        Vec3 zombieFrom = zombie.position();
        EntityMountEvent e = boards(t, boat);
        t.assertEquals(List.of(alice.player(), zombie), boat.getPassengers(), "the boat's riders, driver first");
        assertShiftStated(t, e, zombie, zombieFrom, 0.6);
    }

    /**
     * A zombie facing nearly backwards to {@code boat}, further round than a boat lets a rider face, and out of its
     * reach: a boat takes a mob that touches it aboard by itself.
     */
    private static Zombie facingBackNear(TestContext t, Entity boat) {
        Zombie zombie = spawn(t, EntityType.ZOMBIE, boat.blockPosition().east(3));
        zombie.setYRot(boat.getYRot() + 170.0F);
        zombie.setYHeadRot(boat.getYRot() + 170.0F);
        return zombie;
    }

    /**
     * Listening seats a rider at once, and must do no more than that: a boat turns whoever it seats to face within
     * its limits, on its tick, so a mob that boards facing backwards still does until then, exactly as with nobody
     * listening. (Seating it with the boat's own {@code positionRider} turned it at once, and a player, whom vanilla
     * seats as she boards, twice.)
     */
    @HadronTest
    @Covers(EntityMountEvent.class)
    void aBoardingMobFacesAsItDoesWithNobodyListening(TestContext t) {
        t.player("alice");
        Entity unheard = spawn(t, EntityType.BOAT, t.pos(2, 0, 3));
        Entity heard = spawn(t, EntityType.BOAT, t.pos(2, 0, -3));
        Zombie vanilla = facingBackNear(t, unheard);
        Zombie listened = facingBackNear(t, heard);
        t.tick(2);
        t.assertTrue(vanilla.startRiding(unheard, true), "the zombie should get in the boat nobody listens to");
        List<EntityMountEvent> mounts = hear(t, listened, false);
        t.assertTrue(listened.startRiding(heard, true), "the zombie should get in the boat");
        assertSeatedWhereStated(t, mounts, listened, heard);
        t.assertEquals(vanilla.getYRot(), listened.getYRot(), "the zombie's facing, boarding with a listener, against "
                + "one boarding without");
        t.assertEquals(vanilla.getYHeadRot(), listened.getYHeadRot(), "its head, against the other's");
        t.assertEquals(vanilla.yBodyRot, listened.yBodyRot, "its body, against the other's");
        t.tick(2);
        t.assertEquals(vanilla.getYRot(), listened.getYRot(), "the zombie's facing two ticks later, once each boat "
                + "has turned its rider");
    }

    @HadronTest
    @Covers(EntityMountEvent.class)
    void aMinecartsSeatIsStated(TestContext t) {
        seatsWhereStated(t, MountTests::minecart);
    }

    @HadronTest
    @Covers(EntityMountEvent.class)
    void aHorsesSaddleIsStated(TestContext t) {
        EntityMountEvent e = seatsWhereStated(t, MountTests::horse);
        t.assertTrue(e.getTo().y > e.getMount().getY() + 0.5, "a horse's rider sits above its feet at "
                + e.getMount().position() + ", but the event said " + e.getTo());
    }

    @HadronTest
    @Covers(EntityMountEvent.class)
    void aCamelsSecondSeatIsStatedAndItsFirstRiderStaysPut(TestContext t) {
        EntityMountEvent e = seatsWhereStated(t, withBob(MountTests::camel));
        Vec3 behind = e.getTo().subtract(e.getMount().position());
        t.assertTrue(Math.hypot(behind.x, behind.z) > 0.5, "a camel's second rider sits behind its middle at "
                + e.getMount().position() + ", but the event said " + e.getTo());
    }

    /** Boarding while swimming: she is seated standing, which is higher than a swimmer would attach. */
    @HadronTest
    @Covers(EntityMountEvent.class)
    void aSwimmerIsSeatedAsSheWillSitStanding(TestContext t) {
        TestPlayer alice = t.player("alice");
        Entity boat = boat(t);
        t.tick(2);
        List<EntityMountEvent> heard = hear(t, alice.player(), false);
        alice.player().setPose(Pose.SWIMMING);
        t.assertTrue(alice.player().startRiding(boat), "alice should get in the boat");
        assertSeatedWhereStated(t, heard, alice.player(), boat);
    }

    /** A refused swimmer is still swimming where she was: working out her seat stood her up only to ask. */
    @HadronTest
    @Covers(EntityMountEvent.class)
    void aRefusedSwimmerKeepsSwimming(TestContext t) {
        TestPlayer alice = t.player("alice");
        Entity boat = boat(t);
        t.tick(2);
        List<EntityMountEvent> heard = hear(t, alice.player(), true);
        alice.player().setPose(Pose.SWIMMING);
        Vec3 from = alice.player().position();
        var box = alice.player().getBoundingBox();
        t.assertFalse(alice.player().startRiding(boat), "a refused mount should not seat alice");
        t.assertEquals(1, heard.size(), "alice's mounts");
        t.assertEquals(Pose.SWIMMING, alice.player().getPose(), "alice's pose");
        t.assertEquals(box, alice.player().getBoundingBox(), "alice's box");
        t.assertTrue(alice.player().position().equals(from), "alice should be exactly where she was");
    }

    /** A pig with a zombie on its back, the zombie seated. */
    private static Zombie zombieOn(TestContext t, Pig pig) {
        Zombie zombie = spawn(t, EntityType.ZOMBIE, t.pos(-2, 0, 0));
        t.assertTrue(zombie.startRiding(pig, true), "the zombie should get on the pig");
        t.tick(2);
        return zombie;
    }

    /** A rider with a rider of its own: the event says where each ends up, and each is there as the mount returns. */
    @HadronTest
    @Covers(EntityMountEvent.class)
    void aRidersOwnRiderIsCarriedToItsStatedSeat(TestContext t) {
        t.player("alice");
        Entity boat = boat(t);
        Pig pig = spawn(t, EntityType.PIG, t.pos(-2, 0, 0));
        Zombie zombie = zombieOn(t, pig);
        BlockPos zombieFrom = zombie.blockPosition();
        List<EntityMountEvent> heard = hear(t, pig, false);
        t.assertTrue(pig.startRiding(boat, true), "the pig should get in the boat");
        EntityMountEvent e = assertSeatedWhereStated(t, heard, pig, boat);
        t.assertTrue(e.getCarried() instanceof Carried.Known, "what the pig carries is known: " + e.getCarried());
        List<Carried.Ride> rides = ((Carried.Known) e.getCarried()).rides();
        t.assertEquals(1, rides.size(), "the rides the pig carries");
        t.assertEquals(zombie, rides.get(0).entity(), "who the pig carries");
        t.assertEquals(Place.Spot.at(t.level(), zombieFrom), rides.get(0).from(), "where the zombie sets out");
        t.assertFalse(zombie.blockPosition().equals(zombieFrom), "the zombie should have been carried to the boat, "
                + "but is still in " + zombieFrom.toShortString());
        t.assertEquals(pig, zombie.getVehicle(), "the zombie's vehicle");
        t.assertEquals(Place.Spot.at(t.level(), zombie.blockPosition()), rides.get(0).to(), "where the zombie ends "
                + "up; it is at " + zombie.position());
        t.assertTrue(e.getPlaces().contains(Place.Spot.at(t.level(), zombie.blockPosition())),
                "the event's places should hold the block the zombie ends up in: " + e.getPlaces());
        Vec3 pigSeat = pig.position();
        Vec3 zombieSeat = zombie.position();
        t.tick(2);
        t.assertTrue(zombie.position().distanceTo(zombieSeat.add(pig.position().subtract(pigSeat))) < 1.0E-6,
                "the zombie was seated at once where vanilla seats it on its next tick, but moved from " + zombieSeat
                + " to " + zombie.position());
    }

    @HadronTest
    @Covers(EntityMountEvent.class)
    void aRefusedMountMovesNeitherTheRiderNorWhatItCarries(TestContext t) {
        t.player("alice");
        Entity boat = boat(t);
        Pig pig = spawn(t, EntityType.PIG, t.pos(-2, 0, 0));
        Zombie zombie = zombieOn(t, pig);
        Vec3 pigFrom = pig.position();
        Vec3 zombieFrom = zombie.position();
        List<EntityMountEvent> heard = hear(t, pig, true);
        SideEffects effects = t.watchSideEffects();
        t.assertFalse(pig.startRiding(boat, true), "a refused mount should not seat the pig");
        t.assertEquals(1, heard.size(), "the pig's mounts");
        t.assertTrue(pig.getVehicle() == null && boat.getPassengers().isEmpty(), "nobody should be in the boat, but "
                + boat.getPassengers() + " are");
        t.assertTrue(pig.position().equals(pigFrom), "the pig should be exactly where it was, " + pigFrom
                + ", but is at " + pig.position());
        t.assertEquals(pig, zombie.getVehicle(), "the zombie's vehicle");
        t.assertTrue(zombie.position().equals(zombieFrom), "the zombie should be exactly where it was, " + zombieFrom
                + ", but is at " + zombie.position());
        effects.assertNone("a refused mount");
    }

    @HadronTest
    @Covers(EntityMountEvent.class)
    void aRefusedBoatMountLeavesTheRiderWhereSheWas(TestContext t) {
        refusedStaysOff(t, MountTests::boat);
    }

    @HadronTest
    @Covers(EntityMountEvent.class)
    void aRefusedSecondSeatLeavesTheBoatsRiderAsHeWas(TestContext t) {
        refusedStaysOff(t, withBob(MountTests::boat));
    }

    @HadronTest
    @Covers(EntityMountEvent.class)
    void aRefusedBoardingAheadOfAMobDriverLeavesItDriving(TestContext t) {
        refusedStaysOff(t, withZombie(MountTests::boat));
    }

    @HadronTest
    @Covers(EntityMountEvent.class)
    void aRefusedMinecartMountLeavesTheRiderWhereSheWas(TestContext t) {
        refusedStaysOff(t, MountTests::minecart);
    }

    @HadronTest
    @Covers(EntityMountEvent.class)
    void aRefusedHorseMountLeavesTheRiderWhereSheWas(TestContext t) {
        refusedStaysOff(t, MountTests::horse);
    }

    @HadronTest
    @Covers(EntityMountEvent.class)
    void aRefusedCamelSeatLeavesTheCamelsRiderAsHeWas(TestContext t) {
        refusedStaysOff(t, withBob(MountTests::camel));
    }
}
