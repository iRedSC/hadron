package dev.iredsc.hadron.selftest.entities;

import static dev.iredsc.hadron.selftest.entities.Fixtures.assertPlayer;
import static dev.iredsc.hadron.selftest.entities.Fixtures.console;
import static dev.iredsc.hadron.selftest.entities.Fixtures.ready;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.entity.EntitySpawnEvent;
import dev.iredsc.hadron.api.event.entity.EntitySpawnEvent.Cause;
import dev.iredsc.hadron.api.event.entity.HangingPlaceEvent;
import dev.iredsc.hadron.api.event.entity.ProjectileLaunchEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.MinecartHopper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;

/**
 * {@code EntitySpawnEvent.getCause()}: placed with an item, launched, or spawned any other way, on every vanilla path,
 * with no entity class deciding it.
 */
public final class SpawnCauseTests {
    /** Every spawn of {@code type} heard from now on. */
    private static <E extends Entity> List<EntitySpawnEvent> hear(TestContext t, Class<E> type) {
        List<EntitySpawnEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntitySpawnEvent e) {
                if (type.isInstance(e.getEntity())) events.add(e);
            }
        });
        return events;
    }

    private static EntitySpawnEvent only(TestContext t, List<EntitySpawnEvent> events, String what) {
        t.assertEquals(1, events.size(), "the number of EntitySpawnEvents for " + what);
        return events.get(0);
    }

    private static void assertCause(TestContext t, Cause expected, EntitySpawnEvent e, String what) {
        t.assertEquals(expected, e.getCause(), "getCause() for " + what);
    }

    @HadronTest
    @Covers(EntitySpawnEvent.class)
    void aBoatAPlayerSetsDownIsPlaced(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        alice.player().setXRot(40.0F); // at the floor ahead of her: a boat can't go where she stands
        alice.hold(new ItemStack(Items.OAK_BOAT));
        List<EntitySpawnEvent> events = hear(t, Boat.class);
        alice.useItem();
        EntitySpawnEvent e = only(t, events, "the boat");
        assertCause(t, Cause.PLACED, e, "a boat set down with its item");
        assertPlayer(t, e.getActor(), alice, "A placed boat");
        t.assertTrue(e.getSpawnReason().isEmpty(), "a boat is no mob, so Minecraft's reason says nothing, but was "
                + e.getSpawnReason());
    }

    @HadronTest
    @Covers(EntitySpawnEvent.class)
    void anArmorStandAPlayerSetsDownIsPlaced(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        alice.hold(new ItemStack(Items.ARMOR_STAND));
        List<EntitySpawnEvent> events = hear(t, ArmorStand.class);
        alice.useBlock(t.pos(2, -1, 0));
        EntitySpawnEvent e = only(t, events, "the armor stand");
        assertCause(t, Cause.PLACED, e, "an armor stand set down with its item");
        assertPlayer(t, e.getActor(), alice, "A placed armor stand");
    }

    @HadronTest
    @Covers(EntitySpawnEvent.class)
    void aMinecartSetOnARailIsPlaced(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        BlockPos rail = t.pos(2, 0, 0);
        t.setBlock(rail, Blocks.RAIL);
        alice.hold(new ItemStack(Items.HOPPER_MINECART));
        List<EntitySpawnEvent> events = hear(t, MinecartHopper.class);
        alice.useBlock(rail);
        assertCause(t, Cause.PLACED, only(t, events, "the hopper minecart"), "a minecart set on a rail");
    }

    @HadronTest
    @Covers(HangingPlaceEvent.class)
    void anItemFrameHungIsPlaced(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        BlockPos wall = t.pos(2, 0, 0);
        t.setBlock(wall, Blocks.STONE);
        alice.hold(new ItemStack(Items.ITEM_FRAME));
        List<EntitySpawnEvent> events = hear(t, ItemFrame.class);
        alice.useBlock(wall, Direction.WEST);
        EntitySpawnEvent e = only(t, events, "the item frame");
        t.assertTrue(e instanceof HangingPlaceEvent, "an item frame's spawn should be a HangingPlaceEvent, but was "
                + e.getClass().getSimpleName());
        assertCause(t, Cause.PLACED, e, "an item frame hung with its item");
    }

    @HadronTest
    @Covers(EntitySpawnEvent.class)
    void anEndCrystalSetOnObsidianIsPlaced(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        BlockPos base = t.pos(2, 0, 0);
        t.setBlock(base, Blocks.OBSIDIAN);
        alice.hold(new ItemStack(Items.END_CRYSTAL));
        List<EntitySpawnEvent> events = hear(t, EndCrystal.class);
        alice.useBlock(base, Direction.UP);
        EntitySpawnEvent e = only(t, events, "the end crystal");
        assertCause(t, Cause.PLACED, e, "an end crystal set on obsidian");
        assertPlayer(t, e.getActor(), alice, "A placed end crystal");
    }

    @HadronTest
    @Covers(EntitySpawnEvent.class)
    void aSpawnEggsMobIsPlaced(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        alice.hold(new ItemStack(Items.PIG_SPAWN_EGG));
        List<EntitySpawnEvent> events = hear(t, Pig.class);
        alice.useBlock(t.pos(1, -1, 0));
        assertCause(t, Cause.PLACED, only(t, events, "the egg's pig"), "a spawn egg's mob");
    }

    @HadronTest
    @Covers(EntitySpawnEvent.class)
    void aBoatADispenserSetsDownIsPlacedByTheDispenser(TestContext t) {
        t.player("watcher");
        ready(t);
        BlockPos dispenser = t.pos(2, 0, 0);
        t.setBlock(dispenser, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.EAST));
        ((DispenserBlockEntity) t.level().getBlockEntity(dispenser)).setItem(0, new ItemStack(Items.OAK_BOAT));
        t.setBlock(t.pos(3, -1, 0), Blocks.WATER); // a dispenser sets a boat down only on water
        List<EntitySpawnEvent> events = hear(t, Boat.class);
        t.setBlock(t.pos(2, 0, 1), Blocks.REDSTONE_BLOCK);
        t.await("the dispenser to set the boat down", () -> !events.isEmpty(), 60);
        EntitySpawnEvent e = only(t, events, "the dispenser's boat");
        assertCause(t, Cause.PLACED, e, "a boat a dispenser set down");
        t.assertTrue(e.getActor() instanceof Actor.Machine m && m.pos().equals(dispenser),
                "the actor should be the dispenser's Actor.Machine, but was " + e.getActor());
    }

    @HadronTest
    @Covers(ProjectileLaunchEvent.class)
    void aThrownSnowballIsLaunched(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        alice.hold(new ItemStack(Items.SNOWBALL));
        List<EntitySpawnEvent> events = hear(t, Snowball.class);
        alice.useItem();
        // Thrown from inside the item's use, as a boat is set down: a projectile is launched all the same.
        assertCause(t, Cause.LAUNCHED, only(t, events, "the snowball"), "a thrown snowball");
    }

    @HadronTest
    @Covers(ProjectileLaunchEvent.class)
    void anArrowFromABowIsLaunched(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        alice.hold(new ItemStack(Items.BOW));
        alice.give(new ItemStack(Items.ARROW, 5));
        List<EntitySpawnEvent> events = hear(t, AbstractArrow.class);
        alice.useItem(); // starts drawing
        t.tick(25);
        // The harness has no release action; this is the packet a client sends on letting go of the button.
        alice.player().connection.handlePlayerAction(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, BlockPos.ZERO, Direction.DOWN));
        EntitySpawnEvent e = only(t, events, "the arrow (0 means the bow never fired: it wasn't drawn long enough)");
        assertCause(t, Cause.LAUNCHED, e, "an arrow from a bow");
    }

    @HadronTest
    @Covers(EntitySpawnEvent.class)
    void aNaturalSpawnIsSpawned(TestContext t) {
        t.player("alice");
        ready(t);
        List<EntitySpawnEvent> events = hear(t, Pig.class);
        EntityType.PIG.spawn(t.level(), t.pos(2, 0, 0), MobSpawnType.NATURAL);
        EntitySpawnEvent e = only(t, events, "the pig");
        assertCause(t, Cause.SPAWNED, e, "a mob spawning naturally");
        t.assertEquals(java.util.Optional.of(MobSpawnType.NATURAL), e.getSpawnReason(), "the spawn reason");
    }

    @HadronTest
    @Covers(EntitySpawnEvent.class)
    void aSummonIsSpawnedWhateverItSummons(TestContext t) {
        t.player("alice");
        ready(t);
        List<EntitySpawnEvent> pigs = hear(t, Pig.class);
        List<EntitySpawnEvent> boats = hear(t, Boat.class);
        List<EntitySpawnEvent> stands = hear(t, ArmorStand.class);
        BlockPos at = t.pos(2, 0, 0);
        String where = " " + at.getX() + " " + at.getY() + " " + at.getZ();
        console(t, "summon minecraft:pig" + where);
        console(t, "summon minecraft:boat" + where);
        console(t, "summon minecraft:armor_stand" + where);
        assertCause(t, Cause.SPAWNED, only(t, pigs, "the summoned pig"), "a summoned pig");
        // The same entities a player places: what they are decides nothing, how they came to be does.
        assertCause(t, Cause.SPAWNED, only(t, boats, "the summoned boat"), "a summoned boat");
        assertCause(t, Cause.SPAWNED, only(t, stands, "the summoned armor stand"), "a summoned armor stand");
    }

    @HadronTest
    @Covers(HangingPlaceEvent.class)
    void aSummonedItemFrameOrPaintingIsSpawned(TestContext t) {
        t.player("alice");
        ready(t);
        t.setBlock(t.pos(3, 0, 0), Blocks.STONE);
        t.setBlock(t.pos(3, 0, 2), Blocks.STONE);
        List<EntitySpawnEvent> frames = hear(t, ItemFrame.class);
        List<EntitySpawnEvent> paintings = hear(t, Painting.class);
        BlockPos frame = t.pos(2, 0, 0);
        BlockPos painting = t.pos(2, 0, 2);
        console(t, "summon minecraft:item_frame " + frame.getX() + " " + frame.getY() + " " + frame.getZ()
                + " {Facing:4b}");
        console(t, "summon minecraft:painting " + painting.getX() + " " + painting.getY() + " " + painting.getZ()
                + " {facing:1b,variant:\"minecraft:kebab\"}");
        // Hanging entities like the ones an item hangs: no item's use is behind these, so nothing placed them.
        EntitySpawnEvent framed = only(t, frames, "the summoned item frame");
        t.assertTrue(framed instanceof HangingPlaceEvent, "a summoned item frame's spawn should still be a "
                + "HangingPlaceEvent, but was " + framed.getClass().getSimpleName());
        assertCause(t, Cause.SPAWNED, framed, "a summoned item frame");
        assertCause(t, Cause.SPAWNED, only(t, paintings, "the summoned painting"), "a summoned painting");
    }

    @HadronTest
    @Covers(EntitySpawnEvent.class)
    void tntLitWithFlintAndSteelIsSpawnedNotPlaced(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        BlockPos tnt = t.pos(2, 0, 0);
        t.setBlock(tnt, Blocks.TNT);
        alice.hold(new ItemStack(Items.FLINT_AND_STEEL));
        List<EntitySpawnEvent> events = hear(t, PrimedTnt.class);
        alice.useBlock(tnt);
        EntitySpawnEvent e = only(t, events, "the primed TNT");
        // The block reacted to the item in hand; the flint and steel put nothing in the world itself.
        assertCause(t, Cause.SPAWNED, e, "TNT a player lit");
        e.getEntity().discard();
    }

    @HadronTest
    @Covers(EntitySpawnEvent.class)
    void whatAListenerSpawnsDuringAPlacementIsSpawned(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        alice.hold(new ItemStack(Items.ARMOR_STAND));
        BlockPos pigAt = t.pos(3, 0, 2);
        List<EntitySpawnEvent> pigs = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntitySpawnEvent e) {
                if (e.getEntity() instanceof Pig) pigs.add(e);
                // The listener's own spawn, made while alice's item use is still running.
                if (e.getEntity() instanceof ArmorStand) EntityType.PIG.spawn(t.level(), pigAt, MobSpawnType.TRIGGERED);
            }
        });
        alice.useBlock(t.pos(2, -1, 0));
        assertCause(t, Cause.SPAWNED, only(t, pigs, "the listener's pig"), "a listener's own spawn during a placement");
    }
}
