package dev.iredsc.hadron.selftest.world;

import dev.iredsc.hadron.api.Hadron;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.block.BlockEvent;
import dev.iredsc.hadron.api.world.Area;
import dev.iredsc.hadron.api.world.Areas;
import dev.iredsc.hadron.api.world.WorldCreator;
import dev.iredsc.hadron.api.world.WorldGenerator;
import dev.iredsc.hadron.api.world.Worlds;
import dev.iredsc.hadron.checks.Nondeterministic;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

/**
 * Watched areas: a block change with nobody behind it fires as {@link Actor.Unknown} inside one, and nothing outside,
 * while vanilla's own workings and a plugin's own tasks never look like an outsider's.
 */
public final class AreaTests {
    /** A box from pos(-4, 0, -4) to pos(4, 6, 4) of the test's world. */
    private static BoundingBox around(TestContext t) {
        return BoundingBox.fromCorners(t.pos(-4, 0, -4), t.pos(4, 6, 4));
    }

    private static List<BlockEvent> record(TestContext t, boolean cancel) {
        List<BlockEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockEvent e) {
                seen.add(e);
                if (cancel && e instanceof dev.iredsc.hadron.api.event.Cancellable c) c.setCancelled(true);
            }
        });
        return seen;
    }

    private static List<BlockEvent> unknown(List<BlockEvent> seen) {
        return seen.stream().filter(e -> e.getActor() instanceof Actor.Unknown).toList();
    }

    @HadronTest
    @Covers(value = Areas.class, method = "watch")
    void aChangeWithNobodyBehindItInAWatchedAreaIsUnknown(TestContext t) {
        Areas.watch(t.plugin(), t.level(), around(t));
        List<BlockEvent> seen = record(t, false);
        // The test's own code has nobody on the actor stack, as an unbridged mod's queued work wouldn't.
        t.setBlock(t.pos(1, 1, 1), Blocks.STONE);
        t.assertEquals(1, seen.size(), "the number of block events for one write with nobody behind it");
        t.assertTrue(seen.get(0).getActor() instanceof Actor.Unknown,
                "a write with nobody behind it in a watched area should be Actor.Unknown, but was "
                        + seen.get(0).getActor());
        t.assertBlock(t.pos(1, 1, 1), Blocks.STONE);
    }

    @HadronTest
    @Covers(value = Areas.class, method = "watch")
    void refusingAnUnknownChangeKeepsTheBlock(TestContext t) {
        Areas.watch(t.plugin(), t.level(), around(t));
        List<BlockEvent> seen = record(t, true);
        t.setBlock(t.pos(1, 1, 1), Blocks.STONE);
        t.assertEquals(1, seen.size(), "the number of block events for one refused write");
        t.assertBlock(t.pos(1, 1, 1), Blocks.AIR);
    }

    @HadronTest
    @Covers(value = Areas.class, method = "watch")
    void refusingAnUnknownBreakKeepsTheBlockAndDropsNothing(TestContext t) {
        t.setBlock(t.pos(1, 1, 1), Blocks.STONE);
        Areas.watch(t.plugin(), t.level(), around(t));
        List<BlockEvent> seen = record(t, true);
        t.level().destroyBlock(t.pos(1, 1, 1), true);
        t.assertEquals(1, seen.size(), "the number of block events for one refused break");
        t.assertTrue(seen.get(0).getActor() instanceof Actor.Unknown, "a break with nobody behind it in a watched "
                + "area should be Actor.Unknown, but was " + seen.get(0).getActor());
        t.assertBlock(t.pos(1, 1, 1), Blocks.STONE);
        t.assertTrue(t.level().getEntitiesOfClass(ItemEntity.class, new AABB(t.pos(1, 1, 1)).inflate(2)).isEmpty(),
                "a refused break should drop nothing");
    }

    @HadronTest
    @Covers(value = Areas.class, method = "watch")
    void aFireWithNobodyBehindItInAWatchedAreaIsUnknown(TestContext t) {
        t.setBlock(t.pos(1, 0, 1), Blocks.NETHERRACK);
        Areas.watch(t.plugin(), t.level(), around(t));
        List<BlockEvent> seen = record(t, true);
        t.setBlock(t.pos(1, 1, 1), Blocks.FIRE);
        t.assertEquals(1, seen.size(), "the number of block events for one fire with nobody behind it");
        t.assertTrue(seen.get(0).getActor() instanceof Actor.Unknown, "a fire with nobody behind it in a watched "
                + "area is an outsider's, not nature's, but was " + seen.get(0).getActor());
        t.assertBlock(t.pos(1, 1, 1), Blocks.AIR);
    }

    @HadronTest
    @Covers(value = Area.class, method = "box")
    @SuppressWarnings("deprecation") // BoundingBox.move: the in-place move this test makes sure can't reach the area
    void movingTheBoxAfterwardsDoesntMoveTheArea(TestContext t) {
        BoundingBox box = around(t);
        Area area = Areas.watch(t.plugin(), t.level(), box);
        box.move(100, 0, 0);
        area.box().move(100, 0, 0);
        t.assertEquals(around(t), area.box(), "the area's box after the plugin moved its own copies");
        t.assertTrue(Areas.isWatched(t.level(), t.pos(1, 1, 1)), "the area still watches where it was made");
    }

    @HadronTest
    @Covers(value = Areas.class, method = "watch")
    @Covers(value = Areas.class, method = "isWatched")
    void outsideEveryAreaAChangeWithNobodyBehindItFiresNothing(TestContext t) {
        Areas.watch(t.plugin(), t.level(), around(t));
        List<BlockEvent> seen = record(t, false);
        BlockPos outside = t.pos(6, 1, 0);
        t.assertTrue(Areas.isWatched(t.level(), t.pos(4, 6, 4)), "both corners of a watched box are inside it");
        t.assertTrue(!Areas.isWatched(t.level(), outside), t.describe(outside) + " is outside the box");
        t.setBlock(outside, Blocks.STONE);
        t.assertEquals(0, seen.size(), "the number of block events for a write outside every area");
    }

    @HadronTest
    @Covers(value = Area.class, method = "close")
    @Covers(value = Area.class, method = "isOpen")
    @Covers(value = Area.class, method = "level")
    @Covers(value = Area.class, method = "box")
    void aClosedAreaWatchesNothing(TestContext t) {
        Area area = Areas.watch(t.plugin(), t.level(), around(t));
        t.assertEquals(t.level(), area.level(), "the area's world");
        t.assertEquals(around(t), area.box(), "the area's box");
        t.assertTrue(area.isOpen(), "a new area is open");
        area.close();
        area.close();
        t.assertTrue(!area.isOpen(), "a closed area says it is closed");
        t.assertTrue(!Areas.isWatched(t.level(), t.pos(1, 1, 1)), "nothing is watched once the only area closed");
        List<BlockEvent> seen = record(t, false);
        t.setBlock(t.pos(1, 1, 1), Blocks.STONE);
        t.assertEquals(0, seen.size(), "the number of block events after the area closed");
    }

    @HadronTest
    @Covers(value = Areas.class, method = "watch")
    void overlappingAreasStayWatchedUntilTheLastCloses(TestContext t) {
        Area first = Areas.watch(t.plugin(), t.level(), around(t));
        Area second = Areas.watch(t.plugin(), t.level(), BoundingBox.fromCorners(t.pos(0, 0, 0), t.pos(2, 2, 2)));
        first.close();
        t.assertTrue(Areas.isWatched(t.level(), t.pos(1, 1, 1)), "the second area still watches pos(1, 1, 1)");
        t.assertTrue(!Areas.isWatched(t.level(), t.pos(-3, 1, -3)), "only the first area covered pos(-3, 1, -3)");
        second.close();
        t.assertTrue(!Areas.isWatched(t.level(), t.pos(1, 1, 1)), "nothing is watched once both closed");
    }

    @HadronTest
    @Covers(value = Areas.class, method = "watch")
    void aHugeAreaIsWatchedEverywhereInIt(TestContext t) {
        // Wider than the index lists chunk by chunk, so it takes the other path.
        Area area = Areas.watch(t.plugin(), t.level(),
                BoundingBox.fromCorners(t.pos(-2000, -64, -2000), t.pos(2000, 300, 2000)));
        t.assertTrue(Areas.isWatched(t.level(), t.pos(1999, 0, -1999)), "a corner of the huge area is watched");
        t.assertTrue(!Areas.isWatched(t.level(), t.pos(2001, 0, 0)), "just past its edge isn't");
        area.close();
        t.assertTrue(!Areas.isWatched(t.level(), t.pos(0, 0, 0)), "nothing is watched once it closed");
    }

    @HadronTest
    @Covers(value = Areas.class, method = "watch")
    void aPluginsOwnTaskInAWatchedAreaIsTheServer(TestContext t) {
        Areas.watch(t.plugin(), t.level(), around(t));
        List<BlockEvent> seen = record(t, false);
        Hadron.getScheduler().runTask(t.plugin(), () -> t.setBlock(t.pos(1, 1, 1), Blocks.STONE));
        t.await("the task to place the stone", () -> t.getBlock(t.pos(1, 1, 1)).is(Blocks.STONE), 5);
        t.assertEquals(1, seen.size(), "the number of block events for the task's write");
        t.assertEquals(new Actor.Server("plugin " + t.plugin().getName()), seen.get(0).getActor(),
                "who a plugin's own task acts as");
    }

    @HadronTest
    @Covers(value = Areas.class, method = "watch")
    void anAreaClosesWhenItsWorldUnloads(TestContext t) {
        ResourceLocation key = ResourceLocation.fromNamespaceAndPath("hadronselftest", "areas");
        try {
            ServerLevel level = Worlds.create(new WorldCreator(key).generator(WorldGenerator.voidWorld()));
            Area area = Areas.watch(t.plugin(), level, BoundingBox.fromCorners(BlockPos.ZERO, new BlockPos(4, 4, 4)));
            Worlds.unload(level, false);
            t.tick();
            t.assertTrue(!area.isOpen(), "an area in a world that unloaded is closed");
            try {
                Areas.watch(t.plugin(), level, area.box());
                t.fail("watching in a world that isn't loaded should be refused");
            } catch (IllegalArgumentException expected) {
                t.assertTrue(expected.getMessage().contains("Worlds.load"), "the refusal should say how to load the "
                        + "world, but said: " + expected.getMessage());
            }
        } finally {
            if (Worlds.isCreated(key)) Worlds.delete(key);
        }
    }

    @HadronTest
    @Covers(value = Areas.class, method = "watch")
    @Nondeterministic(reason = "calls Areas from another thread, which takes wall time to run")
    void watchingOffTheServerThreadSaysHowToMoveOnIt(TestContext t) {
        CompletableFuture<String> message = CompletableFuture.supplyAsync(() -> {
            try {
                Areas.watch(t.plugin(), t.level(), around(t));
                return "no exception";
            } catch (IllegalStateException e) {
                return String.valueOf(e.getMessage());
            }
        });
        t.awaitAsync("the other thread to try", message::isDone, 40);
        t.assertTrue(message.join().contains("runTask"), "the refusal should say to use a sync task, but said: "
                + message.join());
    }

    /**
     * Vanilla working on its own inside a watched area: a furnace lighting, a piston pushing and finishing its move,
     * sand falling. None of it has a mod behind it, so none of it is an outsider's, and protection must never refuse
     * it. The test's own set-up writes happen before the area is watched.
     */
    @HadronTest(timeout = 400)
    @Covers(value = Areas.class, method = "watch")
    void vanillaWorkingOnItsOwnInAWatchedAreaIsNeverUnknown(TestContext t) {
        BlockPos furnacePos = t.pos(-2, 1, 0);
        BlockPos piston = t.pos(2, 1, 0);
        BlockPos sand = t.pos(0, 5, 2);
        t.level().setChunkForced(piston.getX() >> 4, piston.getZ() >> 4, true);
        t.awaitAsync("the test world to tick", () -> t.level().isPositionEntityTicking(piston), 300);
        t.setBlock(furnacePos, Blocks.FURNACE);
        FurnaceBlockEntity furnace = (FurnaceBlockEntity) t.level().getBlockEntity(furnacePos);
        furnace.setItem(0, new ItemStack(Items.RAW_IRON));
        furnace.setItem(1, new ItemStack(Items.COAL));
        t.setBlock(piston, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
        t.setBlock(piston.east(), Blocks.STONE);

        Areas.watch(t.plugin(), t.level(), around(t));
        List<BlockEvent> seen = record(t, false);
        // The two writes that start the rest are the test's own, so the only Unknowns allowed are at their spots.
        t.setBlock(piston.west(), Blocks.REDSTONE_BLOCK);
        t.setBlock(sand, Blocks.SAND);
        t.await("the furnace to light", () -> t.getBlock(furnacePos).getValue(FurnaceBlock.LIT), 40);
        t.await("the piston to finish its push", () -> t.getBlock(piston.east(2)).is(Blocks.STONE), 40);
        t.await("the sand to land", () -> t.getBlock(t.pos(0, 0, 2)).is(Blocks.SAND), 100);

        List<BlockEvent> strangers = unknown(seen).stream()
                .filter(e -> !e.getPos().equals(piston.west()) && !e.getPos().equals(sand)).toList();
        t.assertTrue(strangers.isEmpty(), "vanilla working on its own fired as Actor.Unknown: " + strangers.stream()
                .map(e -> e.getClass().getSimpleName() + " at " + t.describe(e.getPos()) + " " + e.getBlocks())
                .toList());
    }
}
