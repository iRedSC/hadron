package dev.iredsc.hadron.selftest.entities;

import static dev.iredsc.hadron.selftest.entities.Fixtures.center;
import static dev.iredsc.hadron.selftest.entities.Fixtures.console;
import static dev.iredsc.hadron.selftest.entities.Fixtures.ready;
import static dev.iredsc.hadron.selftest.entities.Fixtures.spawn;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.event.entity.EntityDismountEvent;
import dev.iredsc.hadron.api.event.entity.EntityEvent;
import dev.iredsc.hadron.api.event.entity.EntityMoveEvent;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent.TeleportCause;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.decoration.PaintingVariants;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Every kind of teleport states where it really lands, and a refused one leaves the entity where it was: chorus fruit,
 * an enderman and a shulker teleporting themselves, a shulker sent with {@code /tp}, a portal, and an ender pearl
 * thrown from a boat. (A player's {@code /tp} and pearl are in {@link TeleportTests}.)
 */
public final class LandingTests {
    /** Every teleport of {@code who} heard from now on; those {@code refuse} picks are cancelled. */
    private static List<EntityTeleportEvent> hear(TestContext t, Entity who, Predicate<EntityTeleportEvent> refuse) {
        List<EntityTeleportEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityTeleportEvent e) {
                if (e.getEntity() != who) return;
                heard.add(e);
                if (refuse.test(e)) e.setCancelled(true);
            }
        });
        return heard;
    }

    /** The event's landing is exactly where {@code entity} is, and its places hold that block. */
    private static void assertLandedWhereStated(TestContext t, EntityTeleportEvent e, Entity entity, String who) {
        t.assertEquals(e.getToLevel(), entity.level(), who + "'s world");
        t.assertTrue(e.getTo().distanceTo(entity.position()) < 1.0E-6, who + " should be where the event said it lands, "
                + e.getTo() + ", but is at " + entity.position());
        BlockPos at = entity.blockPosition();
        t.assertTrue(e.getPlaces().contains(Place.Spot.at((ServerLevel) entity.level(), at)), "the event's places "
                + "should hold " + who + "'s block " + at.toShortString() + ", but are " + e.getPlaces());
    }

    private static void assertStayed(TestContext t, Vec3 from, Entity entity, String who) {
        t.assertEquals(t.level(), entity.level(), who + "'s world");
        t.assertTrue(entity.position().distanceTo(from) < 1.0E-6, who + " should still be at " + from + ", but is at "
                + entity.position());
    }

    /**
     * A second floor 9 below the test floor, wider than chorus fruit's reach, so every spot it picks has ground: one
     * picked below the test floor lands on this one. The tests then don't depend on its random picks.
     */
    private static void groundEverywhere(TestContext t) {
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) t.setBlock(t.pos(x, -10, z), Blocks.STONE);
        }
    }

    private static void eatChorus(TestContext t, TestPlayer who) {
        Items.CHORUS_FRUIT.finishUsingItem(new ItemStack(Items.CHORUS_FRUIT), t.level(), who.player());
    }

    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void chorusFruitStatesWhereTheEaterLands(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        groundEverywhere(t);
        List<EntityTeleportEvent> heard = hear(t, alice.player(), e -> false);
        Vec3 from = alice.player().position();
        eatChorus(t, alice);
        t.assertEquals(1, heard.size(), "the teleports for one chorus fruit that found a spot");
        t.assertEquals(TeleportCause.CHORUS_FRUIT, heard.get(0).getCause(), "the cause");
        t.assertTrue(alice.player().position().distanceTo(from) > 0.01, "alice should have moved");
        assertLandedWhereStated(t, heard.get(0), alice.player(), "alice");
        BlockPos below = alice.player().blockPosition().below();
        t.assertTrue(t.level().getBlockState(below).blocksMotion(), "chorus fruit lands on the ground, but "
                + t.describe(below) + " is " + t.level().getBlockState(below));
    }

    /** Each spot chorus fruit tries is its own landing: refused, it tries the next; all refused, she stays put. */
    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void aRefusedChorusLandingLeavesTheEaterWhereSheWas(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        groundEverywhere(t);
        List<EntityTeleportEvent> heard = hear(t, alice.player(), e -> true);
        Vec3 from = alice.player().position();
        eatChorus(t, alice);
        t.tick();
        t.assertEquals(16, heard.size(), "every one of chorus fruit's 16 spots should be asked about once refused");
        assertStayed(t, from, alice.player(), "alice");
    }

    @HadronTest
    @Covers(EntityTeleportEvent.class)
    @Covers(EntityDismountEvent.class)
    void aRidingChorusEaterGetsOffOnlyOnceALandingGoesAhead(TestContext t) {
        TestPlayer alice = t.player("alice");
        Boat boat = spawn(t, EntityType.BOAT, t.pos(2, 0, 0));
        groundEverywhere(t);
        alice.interact(boat);
        t.assertEquals(boat, alice.player().getVehicle(), "alice's vehicle before eating");
        List<EntityEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityEvent e) {
                if (e.getEntity() == alice.player()
                        && (e instanceof EntityTeleportEvent || e instanceof EntityDismountEvent)) {
                    heard.add(e);
                }
            }
        });
        eatChorus(t, alice);
        t.assertEquals(List.of("EntityTeleportEvent", "EntityDismountEvent"),
                heard.stream().map(e -> e.getClass().getSimpleName()).toList(), "the events, in order");
        t.assertTrue(((EntityDismountEvent) heard.get(1)).isForced(), "getting off for a landing is forced");
        assertLandedWhereStated(t, (EntityTeleportEvent) heard.get(0), alice.player(), "alice");
        t.assertFalse(alice.player().isPassenger(), "alice should be off the boat");
    }

    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void aRidingEaterWhoseEveryLandingIsRefusedStaysRiding(TestContext t) {
        TestPlayer alice = t.player("alice");
        Boat boat = spawn(t, EntityType.BOAT, t.pos(2, 0, 0));
        groundEverywhere(t);
        alice.interact(boat);
        t.assertEquals(boat, alice.player().getVehicle(), "alice's vehicle before eating");
        List<EntityTeleportEvent> heard = hear(t, alice.player(), e -> true);
        List<EntityDismountEvent> off = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityDismountEvent e) {
                off.add(e);
            }
        });
        Vec3 from = alice.player().position();
        eatChorus(t, alice);
        t.assertFalse(heard.isEmpty(), "some landing should have been asked about");
        t.assertEquals(List.of(), off, "the dismounts");
        t.assertEquals(boat, alice.player().getVehicle(), "alice's vehicle");
        assertStayed(t, from, alice.player(), "alice");
    }

    /** {@code EnderMan.teleport(x, y, z)} is private; it is what its AI and its dodging call. */
    private static boolean endermanTeleport(EnderMan enderman, BlockPos to) throws Exception {
        Method teleport = EnderMan.class.getDeclaredMethod("teleport", double.class, double.class, double.class);
        teleport.setAccessible(true);
        return (boolean) teleport.invoke(enderman, to.getX() + 0.5, (double) to.getY(), to.getZ() + 0.5);
    }

    /** Sent three blocks up, it settles on the floor: the event says the floor. */
    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void anEndermanStatesWhereItSettles(TestContext t) throws Exception {
        t.player("alice");
        EnderMan enderman = spawn(t, EntityType.ENDERMAN, t.pos(-3, 0, -3));
        List<EntityTeleportEvent> heard = hear(t, enderman, e -> false);
        BlockPos asked = t.pos(4, 3, 4);
        t.assertTrue(endermanTeleport(enderman, asked), "the enderman should find room above the floor");
        t.assertEquals(1, heard.size(), "the enderman's teleports");
        t.assertEquals(TeleportCause.RANDOM_TELEPORT, heard.get(0).getCause(), "the cause");
        t.assertEquals(t.pos(4, 0, 4), enderman.blockPosition(), "where the enderman settled");
        assertLandedWhereStated(t, heard.get(0), enderman, "the enderman");
    }

    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void aRefusedEndermanStaysWhereItWas(TestContext t) throws Exception {
        t.player("alice");
        EnderMan enderman = spawn(t, EntityType.ENDERMAN, t.pos(-3, 0, -3));
        Vec3 from = enderman.position();
        AABB box = enderman.getBoundingBox().inflate(0.25); // a box only a mod would give it
        enderman.setBoundingBox(box);
        List<EntityTeleportEvent> heard = hear(t, enderman, e -> true);
        t.assertFalse(endermanTeleport(enderman, t.pos(4, 0, 4)), "a refused teleport reports it didn't go");
        t.assertEquals(1, heard.size(), "the enderman's teleports");
        assertStayed(t, from, enderman, "the enderman");
        t.assertEquals(box, enderman.getBoundingBox(), "the enderman's box, which the search must leave as it was");
    }

    /** A listener's {@code setTo} is exact: sent into the air, it is put there, not on the floor below. */
    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void aRedirectedEndermanLandsExactlyWhereItWasSent(TestContext t) throws Exception {
        t.player("alice");
        EnderMan enderman = spawn(t, EntityType.ENDERMAN, t.pos(-3, 0, -3));
        Vec3 sent = Vec3.atBottomCenterOf(t.pos(-4, 5, 4)).add(0.2, 0.3, -0.1);
        List<EntityTeleportEvent> heard = hear(t, enderman, e -> {
            e.setTo(e.getToLevel(), sent, 90f, 30f);
            return false;
        });
        t.assertTrue(endermanTeleport(enderman, t.pos(4, 3, 4)), "the enderman should find room above the floor");
        t.assertEquals(1, heard.size(), "the enderman's teleports");
        t.assertTrue(enderman.position().distanceTo(sent) < 1.0E-6, "the enderman should be exactly at " + sent
                + ", but is at " + enderman.position());
        t.assertEquals(90f, enderman.getYRot(), "the way it was turned");
        t.assertEquals(30f, enderman.getXRot(), "how far it was tilted");
    }

    /** A spot with no room fires nothing and moves nothing: nothing would land there. */
    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void aRandomTeleportWithNoRoomFiresNothing(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        BlockPos spot = t.pos(4, 0, 4);
        t.setBlock(spot.above(), Blocks.STONE); // the floor is below the spot, but alice won't fit under the stone
        List<EntityTeleportEvent> heard = hear(t, alice.player(), e -> false);
        Vec3 from = alice.player().position();
        boolean moved = alice.player().randomTeleport(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, false);
        t.assertFalse(moved, "whether alice found room");
        t.assertEquals(List.of(), heard, "the teleports for a spot with no room");
        assertStayed(t, from, alice.player(), "alice");
    }

    /** {@code Shulker.teleportSomewhere()} is protected; it is what its hurt and its AI call. Up to 50 tries. */
    private static boolean shulkerTeleport(Shulker shulker) throws Exception {
        Method teleport = Shulker.class.getDeclaredMethod("teleportSomewhere");
        teleport.setAccessible(true);
        for (int i = 0; i < 50; i++) {
            if ((boolean) teleport.invoke(shulker)) return true;
        }
        return false;
    }

    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void aShulkerStatesTheBlockCentreItLandsOn(TestContext t) throws Exception {
        t.player("alice");
        Shulker shulker = spawn(t, EntityType.SHULKER, t.pos(0, 0, 3));
        shulker.setNoAi(false); // a shulker with no AI never teleports
        List<EntityTeleportEvent> heard = hear(t, shulker, e -> false);
        t.assertTrue(shulkerTeleport(shulker), "the shulker should find somewhere to go in 50 tries");
        t.assertEquals(1, heard.size(), "the shulker's teleports");
        t.assertEquals(TeleportCause.RANDOM_TELEPORT, heard.get(0).getCause(), "the cause");
        assertLandedWhereStated(t, heard.get(0), shulker, "the shulker");
    }

    /** Its own teleport is placed by no teleport method, so nothing of it may wait for the next one. */
    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void aShulkerSentOnInTheSameTickIsAskedAgain(TestContext t) throws Exception {
        t.player("alice");
        Shulker shulker = spawn(t, EntityType.SHULKER, t.pos(0, 0, 3));
        shulker.setNoAi(false);
        List<EntityTeleportEvent> heard = hear(t, shulker, e -> e.getCause() != TeleportCause.RANDOM_TELEPORT);
        t.assertTrue(shulkerTeleport(shulker), "the shulker should find somewhere to go in 50 tries");
        Vec3 landed = shulker.position();
        shulker.teleportTo(landed.x + 2, landed.y, landed.z);
        t.assertEquals(2, heard.size(), "the shulker's teleports, its own and the one sent after it");
        assertStayed(t, landed, shulker, "the shulker, whose second teleport was refused");
    }

    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void aRefusedShulkerStaysWhereItWas(TestContext t) throws Exception {
        t.player("alice");
        Shulker shulker = spawn(t, EntityType.SHULKER, t.pos(0, 0, 3));
        shulker.setNoAi(false);
        Vec3 from = shulker.position();
        List<EntityTeleportEvent> heard = hear(t, shulker, e -> true);
        t.assertFalse(shulkerTeleport(shulker), "a shulker whose every teleport is refused goes nowhere");
        t.assertFalse(heard.isEmpty(), "its teleports should have been asked about");
        assertStayed(t, from, shulker, "the shulker");
    }

    /** A riding shulker's own teleport takes it off first, so it lands at a block's centre like any other. */
    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void aRidingShulkerStatesTheBlockCentreItLandsOn(TestContext t) throws Exception {
        t.player("alice");
        Boat boat = spawn(t, EntityType.BOAT, t.pos(0, 0, 3));
        Shulker shulker = spawn(t, EntityType.SHULKER, t.pos(0, 0, 3));
        shulker.setNoAi(false);
        t.assertTrue(shulker.startRiding(boat, true), "the shulker should get in the boat");
        t.tick(2);
        List<EntityTeleportEvent> heard = hear(t, shulker, e -> false);
        t.assertTrue(shulkerTeleport(shulker), "the shulker should find somewhere to go in 50 tries");
        t.assertEquals(1, heard.size(), "the shulker's teleports");
        t.assertFalse(shulker.isPassenger(), "the shulker should be out of the boat");
        assertLandedWhereStated(t, heard.get(0), shulker, "the shulker");
    }

    /** {@code Shulker.getRawPeekAmount} and {@code setRawPeekAmount} aren't public: how far its shell is open. */
    private static int peek(Shulker shulker) throws Exception {
        Method get = Shulker.class.getDeclaredMethod("getRawPeekAmount");
        get.setAccessible(true);
        return (int) get.invoke(shulker);
    }

    private static void openShell(Shulker shulker) throws Exception {
        Method set = Shulker.class.getDeclaredMethod("setRawPeekAmount", int.class);
        set.setAccessible(true);
        set.invoke(shulker, 100);
    }

    /**
     * A shulker sent by a mod's {@code randomTeleport}: the search for room moves nothing it has, so refused, its shell
     * stays open and it stays put.
     */
    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void aRefusedRandomTeleportLeavesAShulkerAsItWas(TestContext t) throws Exception {
        t.player("alice");
        Shulker shulker = spawn(t, EntityType.SHULKER, t.pos(0, 0, 3));
        t.tick(2); // a shulker's own placing only closes its shell once it has ticked
        openShell(shulker);
        Vec3 from = shulker.position();
        List<EntityTeleportEvent> heard = hear(t, shulker, e -> true);
        BlockPos spot = t.pos(4, 0, -2);
        t.assertFalse(shulker.randomTeleport(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, false),
                "a refused random teleport reports it didn't go");
        t.assertEquals(1, heard.size(), "the shulker's teleports");
        t.assertEquals(100, peek(shulker), "how far the shulker's shell is open");
        assertStayed(t, from, shulker, "the shulker");
    }

    /** The search checks for room where the shulker will sit, the block's centre, not the spot it was aimed at. */
    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void aRandomTeleportFindsNoRoomWhereAShulkersCentredBoxWontFit(TestContext t) {
        t.player("alice");
        Shulker shulker = spawn(t, EntityType.SHULKER, t.pos(-3, 0, 3));
        Vec3 from = shulker.position();
        BlockPos spot = t.pos(4, 0, -2);
        // An open trapdoor on the west edge: clear of a box at x=.99, in the way of one at the centre.
        t.level().setBlock(spot, Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.OPEN, true)
                .setValue(TrapDoorBlock.FACING, Direction.EAST), 2);
        List<EntityTeleportEvent> heard = hear(t, shulker, e -> false);
        t.assertFalse(shulker.randomTeleport(spot.getX() + 0.99, spot.getY(), spot.getZ() + 0.5, false),
                "a shulker shouldn't land where its centred box meets the trapdoor");
        t.assertEquals(0, heard.size(), "teleports asked about with no room to land");
        assertStayed(t, from, shulker, "the shulker");
    }

    /** {@code /tp} to the side of a block: a shulker sits at its centre, and the event says so. */
    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void aShulkerSentWithTpStatesTheCentreItSitsAt(TestContext t) {
        t.player("alice");
        Shulker shulker = spawn(t, EntityType.SHULKER, t.pos(0, 0, 3));
        List<EntityTeleportEvent> heard = hear(t, shulker, e -> false);
        BlockPos block = t.pos(4, 0, -2);
        console(t, "tp " + shulker.getStringUUID() + " " + (block.getX() + 0.2) + " " + (block.getY() + 0.6) + " "
                + (block.getZ() + 0.9));
        t.assertEquals(1, heard.size(), "the shulker's teleports");
        t.assertEquals(TeleportCause.COMMAND, heard.get(0).getCause(), "the cause");
        t.assertEquals(block.above(), shulker.blockPosition(), "the block the shulker sits in, rounded up");
        assertLandedWhereStated(t, heard.get(0), shulker, "the shulker");
    }

    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void aRefusedTpLeavesAShulkerWhereItWas(TestContext t) {
        t.player("alice");
        Shulker shulker = spawn(t, EntityType.SHULKER, t.pos(0, 0, 3));
        Vec3 from = shulker.position();
        List<EntityTeleportEvent> heard = hear(t, shulker, e -> true);
        console(t, "tp " + shulker.getStringUUID() + " " + coords(center(t.pos(4, 0, -2))));
        t.assertEquals(1, heard.size(), "the shulker's teleports");
        assertStayed(t, from, shulker, "the shulker");
    }

    private static String coords(Vec3 v) {
        return v.x + " " + v.y + " " + v.z;
    }

    /** A 2x2 painting at the test world's centre, sent with {@code /tp}; returns it where it ended up. */
    private static Painting tpPainting(TestContext t) {
        Painting painting = spawn(t, EntityType.PAINTING, t.pos(0, 1, 0));
        painting.setVariant(t.level().registryAccess().registryOrThrow(Registries.PAINTING_VARIANT)
                .getHolderOrThrow(PaintingVariants.MATCH));
        console(t, "tp " + painting.getStringUUID() + " " + coords(center(t.pos(4, 2, 4)).add(0.2, 0.3, 0.1)));
        return painting;
    }

    /**
     * A painting places itself by its own rule, off the spot it is sent to; a listener refusing every move must not
     * shift it from where it lands with no such listener.
     */
    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void aPaintingSentWithTpLandsTheSameWhenEveryMoveIsRefused(TestContext t) {
        t.player("alice");
        Painting alone = tpPainting(t);
        BlockPos anchor = alone.getPos();
        Vec3 landed = alone.position();
        alone.discard();
        List<EntityTeleportEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityTeleportEvent e) {
                if (e.getEntity() instanceof Painting) heard.add(e);
            }

            @EventHandler
            void on(EntityMoveEvent e) {
                e.setCancelled(true);
            }
        });
        Painting refused = tpPainting(t);
        t.assertEquals(1, heard.size(), "the painting's teleports");
        t.assertEquals(anchor, refused.getPos(), "the block the painting hangs at");
        t.assertEquals(landed, refused.position(), "where the painting is");
    }

    /** A nether portal in the test world, two wide and three high, along x; returns its lower west portal block. */
    private static BlockPos portal(TestContext t) {
        BlockPos base = t.pos(4, 0, 0);
        for (int x = -1; x <= 2; x++) {
            t.setBlock(base.offset(x, -1, 0), Blocks.OBSIDIAN);
            t.setBlock(base.offset(x, 3, 0), Blocks.OBSIDIAN);
        }
        for (int y = 0; y < 3; y++) {
            t.setBlock(base.offset(-1, y, 0), Blocks.OBSIDIAN);
            t.setBlock(base.offset(2, y, 0), Blocks.OBSIDIAN);
        }
        var inside = Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, Direction.Axis.X);
        for (int x = 0; x <= 1; x++) {
            for (int y = 0; y < 3; y++) t.level().setBlock(base.offset(x, y, 0), inside, 2);
        }
        return base;
    }

    /** Keeps {@code entity} in the portal at {@code at}, as standing in it does, until {@code done}. */
    private static void stayInPortal(TestContext t, Entity entity, BlockPos at, java.util.function.BooleanSupplier done) {
        t.await("the portal to act", () -> {
            if (!entity.isRemoved() && entity.level() == t.level()) {
                entity.setAsInsidePortal((Portal) Blocks.NETHER_PORTAL, at);
            }
            return done.getAsBoolean();
        }, 200);
    }

    @HadronTest(timeout = 600)
    @Covers(EntityTeleportEvent.class)
    void aPortalStatesWhereItsTravellerArrives(TestContext t) {
        t.player("alice");
        ServerLevel nether = t.server().getLevel(Level.NETHER);
        t.assertTrue(nether != null, "the test server has a Nether");
        BlockPos at = portal(t);
        Pig pig = spawn(t, EntityType.PIG, at);
        List<EntityTeleportEvent> heard = hear(t, pig, e -> false);
        stayInPortal(t, pig, at, () -> pig.isRemoved());
        t.assertEquals(1, heard.size(), "the pig's teleports");
        EntityTeleportEvent e = heard.get(0);
        t.assertEquals(TeleportCause.PORTAL, e.getCause(), "the cause");
        t.assertEquals(nether, e.getToLevel(), "the world it goes to");
        Entity arrived = nether.getEntity(pig.getUUID());
        t.assertTrue(arrived != null, "the pig should be in the Nether");
        assertLandedWhereStated(t, e, arrived, "the pig");
    }

    @HadronTest(timeout = 600)
    @Covers(EntityTeleportEvent.class)
    void aRefusedPortalLeavesItsTravellerWhereItWas(TestContext t) {
        t.player("alice");
        BlockPos at = portal(t);
        Pig pig = spawn(t, EntityType.PIG, at);
        Vec3 from = pig.position();
        List<EntityTeleportEvent> heard = hear(t, pig, e -> true);
        stayInPortal(t, pig, at, () -> !heard.isEmpty());
        t.tick(2);
        t.assertFalse(pig.isRemoved(), "the pig should not have left");
        assertStayed(t, from, pig, "the pig");
    }

    /** Alice throws a pearl ahead of her, down at the floor. */
    private static void throwPearl(TestPlayer alice) {
        alice.player().setYRot(0.0F);
        alice.player().setXRot(40.0F);
        alice.hold(new ItemStack(Items.ENDER_PEARL, 2));
        alice.useItem();
    }

    @HadronTest
    @Covers(EntityTeleportEvent.class)
    @Covers(EntityDismountEvent.class)
    void aPearlThrownFromABoatTakesTheThrowerOffOnlyOnceItGoesAhead(TestContext t) {
        TestPlayer alice = t.player("alice");
        Boat boat = spawn(t, EntityType.BOAT, t.pos(2, 0, 0));
        alice.interact(boat);
        t.assertEquals(boat, alice.player().getVehicle(), "alice's vehicle before throwing");
        List<EntityEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityEvent e) {
                if (e.getEntity() == alice.player()
                        && (e instanceof EntityTeleportEvent || e instanceof EntityDismountEvent)) {
                    heard.add(e);
                }
            }
        });
        throwPearl(alice);
        t.await("the pearl to land", () -> !heard.isEmpty(), 60);
        t.assertEquals(List.of("EntityTeleportEvent", "EntityDismountEvent"),
                heard.stream().map(e -> e.getClass().getSimpleName()).toList(), "the events, in order");
        t.assertFalse(alice.player().isPassenger(), "alice should be off the boat");
        assertLandedWhereStated(t, (EntityTeleportEvent) heard.get(0), alice.player(), "alice");
    }

    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void aRefusedPearlLeavesTheThrowerInTheBoat(TestContext t) {
        TestPlayer alice = t.player("alice");
        Boat boat = spawn(t, EntityType.BOAT, t.pos(2, 0, 0));
        alice.interact(boat);
        t.assertEquals(boat, alice.player().getVehicle(), "alice's vehicle before throwing");
        List<EntityTeleportEvent> heard = hear(t, alice.player(), e -> true);
        Vec3 from = alice.player().position();
        throwPearl(alice);
        t.await("the pearl to land", () -> !heard.isEmpty(), 60);
        t.tick();
        t.assertEquals(boat, alice.player().getVehicle(), "alice's vehicle");
        assertStayed(t, from, alice.player(), "alice");
    }

    /** A mob's own pearl, thrown from a boat: refused, the mob stays in the boat, where it was. */
    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void aRefusedPearlLeavesAMobThrowerInTheBoat(TestContext t) {
        t.player("alice");
        Boat boat = spawn(t, EntityType.BOAT, t.pos(2, 0, 0));
        Pig pig = spawn(t, EntityType.PIG, t.pos(2, 0, 0));
        t.assertTrue(pig.startRiding(boat, true), "the pig should get in the boat");
        t.tick(2);
        Vec3 from = pig.position();
        List<EntityTeleportEvent> heard = hear(t, pig, e -> true);
        var pearl = new net.minecraft.world.entity.projectile.ThrownEnderpearl(t.level(), pig);
        Vec3 drop = center(t.pos(-3, 2, 3));
        pearl.setPos(drop.x, drop.y, drop.z);
        pearl.setDeltaMovement(0, -1, 0);
        t.level().addFreshEntity(pearl);
        t.await("the pearl to land", () -> !heard.isEmpty(), 60);
        t.tick();
        t.assertEquals(boat, pig.getVehicle(), "the pig's vehicle");
        assertStayed(t, from, pig, "the pig");
    }

    /** A spot in the air above the overworld's spawn: another world than the test's, in a chunk that is loaded. */
    private static Vec3 overworldSpot(TestContext t) {
        BlockPos spawn = t.server().overworld().getSharedSpawnPos();
        return new Vec3(spawn.getX() + 0.3, 300.25, spawn.getZ() + 0.7);
    }

    /**
     * Alice's pearl, gone through a portal since she threw it, lands at {@code spot} in the overworld.
     * {@code ThrownEnderpearl.onHit} is protected; it is what a pearl's tick calls when it meets a block.
     */
    private static void landPearlInOverworld(TestContext t, TestPlayer alice, Vec3 spot) throws Exception {
        var pearl = new net.minecraft.world.entity.projectile.ThrownEnderpearl(t.server().overworld(), alice.player());
        pearl.setPos(spot.x, spot.y, spot.z);
        Method onHit = net.minecraft.world.entity.projectile.ThrownEnderpearl.class.getDeclaredMethod("onHit",
                net.minecraft.world.phys.HitResult.class);
        onHit.setAccessible(true);
        onHit.invoke(pearl, new net.minecraft.world.phys.BlockHitResult(spot, Direction.UP, BlockPos.containing(spot),
                false));
    }

    /** A pearl that left its thrower's world still takes the thrower: to the pearl's world, which the event states. */
    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void aPearlInAnotherWorldStatesThatWorldAndTheThrowerLandsThere(TestContext t) throws Exception {
        TestPlayer alice = t.player("alice");
        ready(t);
        ServerLevel overworld = t.server().overworld();
        t.assertTrue(overworld != t.level(), "the test's world isn't the overworld");
        Vec3 spot = overworldSpot(t);
        List<EntityTeleportEvent> heard = hear(t, alice.player(), e -> false);
        landPearlInOverworld(t, alice, spot);
        t.assertEquals(1, heard.size(), "alice's teleports");
        EntityTeleportEvent e = heard.get(0);
        t.assertEquals(TeleportCause.ENDER_PEARL, e.getCause(), "the cause");
        t.assertEquals(overworld, e.getToLevel(), "the world the event says she lands in");
        t.assertEquals(overworld, alice.player().level(), "the world alice is in");
        t.assertTrue(alice.player().position().distanceTo(spot) < 1.0E-6, "alice should be exactly where the pearl "
                + "landed, " + spot + ", but is at " + alice.player().position());
        assertLandedWhereStated(t, e, alice.player(), "alice");
    }

    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void aRefusedPearlInAnotherWorldLeavesTheThrowerWhereSheWas(TestContext t) throws Exception {
        TestPlayer alice = t.player("alice");
        ready(t);
        Vec3 from = alice.player().position();
        float health = alice.player().getHealth();
        List<EntityTeleportEvent> heard = hear(t, alice.player(), e -> true);
        landPearlInOverworld(t, alice, overworldSpot(t));
        t.tick();
        t.assertEquals(1, heard.size(), "alice's teleports");
        assertStayed(t, from, alice.player(), "alice");
        t.assertEquals(health, alice.player().getHealth(), "alice's health: a refused pearl deals no fall damage");
    }

    /** Sent back to her own world by a listener, she lands exactly there, though the pearl is in another. */
    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void aPearlInAnotherWorldRedirectedHomeLandsTheThrowerWhereSent(TestContext t) throws Exception {
        TestPlayer alice = t.player("alice");
        ready(t);
        Vec3 sent = center(t.pos(-4, 0, 4)).add(0.2, 0.0, -0.1);
        List<EntityTeleportEvent> heard = hear(t, alice.player(), e -> {
            e.setTo(t.level(), sent);
            return false;
        });
        landPearlInOverworld(t, alice, overworldSpot(t));
        t.assertEquals(1, heard.size(), "alice's teleports");
        t.assertEquals(t.level(), alice.player().level(), "the world alice is in");
        t.assertTrue(alice.player().position().distanceTo(sent) < 1.0E-6, "alice should be exactly at " + sent
                + ", but is at " + alice.player().position());
    }
}
