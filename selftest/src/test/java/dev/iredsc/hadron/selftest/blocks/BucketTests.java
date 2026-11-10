package dev.iredsc.hadron.selftest.blocks;

import static dev.iredsc.hadron.selftest.blocks.Fixtures.assertMachineAt;
import static dev.iredsc.hadron.selftest.blocks.Fixtures.assertPlayer;
import static dev.iredsc.hadron.selftest.blocks.Fixtures.dispenser;
import static dev.iredsc.hadron.selftest.blocks.Fixtures.fire;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.block.BlockChangeEvent;
import dev.iredsc.hadron.api.event.block.BucketEmptyEvent;
import dev.iredsc.hadron.api.event.block.BucketFillEvent;
import dev.iredsc.hadron.api.event.entity.EntitySpawnEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.SideEffects;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.animal.Cod;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;

/** Buckets poured and filled by players and dispensers. */
public final class BucketTests {
    private static List<BlockChangeEvent> recordChanges(TestContext t) {
        List<BlockChangeEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockChangeEvent e) {
                seen.add(e);
            }
        });
        return seen;
    }

    private static String classes(List<? extends Object> events) {
        return events.stream().map(e -> e.getClass().getSimpleName()).toList().toString();
    }

    /**
     * Floats alice one block above {@code at}, looking straight down, so a bucket's ray hits {@code at} (or the floor
     * under it when {@code at} is air). The harness has no "look at" action; the use-item packet carries her rotation.
     */
    private static void aimDownAt(TestPlayer alice, BlockPos at) {
        alice.teleport(at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5);
        alice.player().setXRot(90f);
    }

    @HadronTest
    @Covers(BucketEmptyEvent.class)
    void pouringABucketIsOneBucketEmptyByThePlayer(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos at = t.pos(2, 0, 0);
        aimDownAt(alice, at);
        alice.hold(new ItemStack(Items.WATER_BUCKET));
        List<BlockChangeEvent> seen = recordChanges(t);
        alice.useItem();
        t.assertBlock(at, Blocks.WATER);
        t.assertHolding(alice, Items.BUCKET, 1);
        t.assertEquals("[BucketEmptyEvent]", classes(seen), "the block change events for pouring one bucket");
        assertPlayer(t, seen.get(0).getActor(), alice);
        t.assertEquals(at, seen.get(0).getPos(), "where the bucket pours");
    }

    @HadronTest
    @Covers(BucketEmptyEvent.class)
    void cancelledPourPlacesNoWaterAndKeepsTheBucketFull(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos at = t.pos(2, 0, 0);
        aimDownAt(alice, at);
        alice.hold(new ItemStack(Items.WATER_BUCKET));
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BucketEmptyEvent e) {
                e.setCancelled(true);
            }
        });
        SideEffects effects = t.watchSideEffects();
        alice.useItem();
        t.tick(5);
        t.assertBlock(at, Blocks.AIR);
        t.assertHolding(alice, Items.WATER_BUCKET, 1);
        effects.assertNone("a refused pour");
    }

    @HadronTest
    @Covers(BucketFillEvent.class)
    void fillingABucketIsOneBucketFillByThePlayer(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos at = t.pos(2, 0, 0);
        t.setBlock(at, Blocks.WATER);
        aimDownAt(alice, at);
        alice.hold(new ItemStack(Items.BUCKET));
        List<BlockChangeEvent> seen = recordChanges(t);
        alice.useItem();
        t.assertBlock(at, Blocks.AIR);
        t.assertHolding(alice, Items.WATER_BUCKET, 1);
        t.assertEquals("[BucketFillEvent]", classes(seen), "the block change events for filling one bucket");
        assertPlayer(t, seen.get(0).getActor(), alice);
    }

    @HadronTest
    @Covers(BucketFillEvent.class)
    void cancelledFillLeavesTheWaterAndTheBucketEmpty(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos at = t.pos(2, 0, 0);
        t.setBlock(at, Blocks.WATER);
        aimDownAt(alice, at);
        alice.hold(new ItemStack(Items.BUCKET));
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BucketFillEvent e) {
                e.setCancelled(true);
            }
        });
        SideEffects effects = t.watchSideEffects();
        alice.useItem();
        t.tick();
        t.assertBlock(at, Blocks.WATER);
        t.assertTrue(t.level().getFluidState(at).isSource(), "the water should still be a source");
        t.assertHolding(alice, Items.BUCKET, 1);
        effects.assertNone("a refused fill");
    }

    // ---- Dispensers

    @HadronTest
    @Covers(BucketEmptyEvent.class)
    void aDispenserPouringIsOneBucketEmptyByTheMachine(TestContext t) {
        BlockPos at = t.pos(2, 0, 0);
        DispenserBlockEntity dispenser = dispenser(t, at, Direction.EAST, new ItemStack(Items.WATER_BUCKET));
        List<BlockChangeEvent> seen = recordChanges(t);
        fire(t, at, Direction.EAST);
        t.assertBlock(at.east(), Blocks.WATER);
        t.assertTrue(dispenser.getItem(0).is(Items.BUCKET), "the dispenser should hold the emptied bucket, but "
                + "holds " + dispenser.getItem(0));
        List<BlockChangeEvent> buckets = seen.stream().filter(e -> e instanceof BucketEmptyEvent).toList();
        t.assertEquals(1, buckets.size(), "the number of BucketEmptyEvents (all block changes: " + classes(seen)
                + ")");
        assertMachineAt(t, buckets.get(0).getActor(), at);
    }

    @HadronTest
    @Covers(BucketEmptyEvent.class)
    void cancelledDispenserPourKeepsTheWaterBucketInside(TestContext t) {
        BlockPos at = t.pos(2, 0, 0);
        DispenserBlockEntity dispenser = dispenser(t, at, Direction.EAST, new ItemStack(Items.WATER_BUCKET));
        List<BucketEmptyEvent> tried = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BucketEmptyEvent e) {
                tried.add(e);
                e.setCancelled(true);
            }
        });
        SideEffects effects = t.watchSideEffects();
        fire(t, at, Direction.EAST);
        t.assertEquals(1, tried.size(), "the dispenser's attempts to pour");
        t.assertBlock(at.east(), Blocks.AIR);
        t.assertTrue(dispenser.getItem(0).is(Items.WATER_BUCKET), "the dispenser should still hold the water "
                + "bucket, but holds " + dispenser.getItem(0));
        t.assertEquals(0, Fixtures.entities(t, ItemEntity.class, at, 3), "items thrown out of the dispenser");
        effects.assertNone("a dispenser's refused pour");
    }

    @HadronTest
    @Covers(BucketFillEvent.class)
    void aDispenserFillingIsOneBucketFillByTheMachine(TestContext t) {
        BlockPos at = t.pos(2, 0, 0);
        t.setBlock(at.east(), Blocks.WATER);
        DispenserBlockEntity dispenser = dispenser(t, at, Direction.EAST, new ItemStack(Items.BUCKET));
        List<BucketFillEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BucketFillEvent e) {
                seen.add(e);
            }
        });
        fire(t, at, Direction.EAST);
        t.assertFalse(t.level().getFluidState(at.east()).isSource(), "the water source should have been taken");
        t.assertTrue(dispenser.getItem(0).is(Items.WATER_BUCKET), "the dispenser should hold a water bucket, but "
                + "holds " + dispenser.getItem(0));
        t.assertEquals(1, seen.size(), "the number of BucketFillEvents");
        assertMachineAt(t, seen.get(0).getActor(), at);
    }

    @HadronTest
    @Covers(BucketFillEvent.class)
    void cancelledDispenserFillLeavesTheWaterAndTheBucketInside(TestContext t) {
        BlockPos at = t.pos(2, 0, 0);
        t.setBlock(at.east(), Blocks.WATER);
        DispenserBlockEntity dispenser = dispenser(t, at, Direction.EAST, new ItemStack(Items.BUCKET));
        List<BucketFillEvent> tried = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BucketFillEvent e) {
                tried.add(e);
                e.setCancelled(true);
            }
        });
        SideEffects effects = t.watchSideEffects();
        fire(t, at, Direction.EAST);
        t.assertEquals(1, tried.size(), "the dispenser's attempts to fill");
        t.assertTrue(t.level().getFluidState(at.east()).isSource(), "the water source should still be there");
        t.assertTrue(dispenser.getItem(0).is(Items.BUCKET) && dispenser.getItem(0).getCount() == 1,
                "the dispenser should still hold its one empty bucket, but holds " + dispenser.getItem(0));
        t.assertEquals(0, Fixtures.entities(t, ItemEntity.class, at, 3), "items thrown out of the dispenser");
        effects.assertNone("a dispenser's refused fill");
    }

    // ---- Buckets holding a mob

    /** Refuses every cod's spawn. */
    private static void refuseCod(TestContext t) {
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntitySpawnEvent e) {
                if (e.getEntity() instanceof Cod) e.setCancelled(true);
            }
        });
    }

    @HadronTest
    @Covers(EntitySpawnEvent.class)
    void refusedCodFromABucketPoursNoWaterAndKeepsTheBucket(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos at = t.pos(2, 0, 0);
        aimDownAt(alice, at);
        alice.hold(new ItemStack(Items.COD_BUCKET));
        refuseCod(t);
        SideEffects effects = t.watchSideEffects();
        alice.useItem();
        t.tick(5);
        t.assertBlock(at, Blocks.AIR);
        t.assertHolding(alice, Items.COD_BUCKET, 1);
        t.assertEquals(0, Fixtures.entities(t, Cod.class, at, 3), "cod released");
        effects.assertNone("a refused cod from a bucket");
    }

    @HadronTest
    @Covers(EntitySpawnEvent.class)
    void refusedCodFromADispenserPoursNoWaterAndKeepsTheBucketInside(TestContext t) {
        BlockPos at = t.pos(2, 0, 0);
        DispenserBlockEntity dispenser = dispenser(t, at, Direction.EAST, new ItemStack(Items.COD_BUCKET));
        refuseCod(t);
        SideEffects effects = t.watchSideEffects();
        fire(t, at, Direction.EAST);
        t.assertBlock(at.east(), Blocks.AIR);
        t.assertTrue(dispenser.getItem(0).is(Items.COD_BUCKET), "the dispenser should still hold the cod bucket, "
                + "but holds " + dispenser.getItem(0));
        t.assertEquals(0, Fixtures.entities(t, Cod.class, at, 3), "cod released");
        effects.assertNone("a refused cod from a dispenser");
    }
}
