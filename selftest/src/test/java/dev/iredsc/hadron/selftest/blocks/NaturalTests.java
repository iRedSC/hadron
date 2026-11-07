package dev.iredsc.hadron.selftest.blocks;

import static dev.iredsc.hadron.selftest.blocks.Fixtures.assertNature;
import static dev.iredsc.hadron.selftest.blocks.Fixtures.assertPlayer;
import static dev.iredsc.hadron.selftest.blocks.Fixtures.withRandomTicks;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.block.BlockChangeEvent;
import dev.iredsc.hadron.api.event.block.BlockFadeEvent;
import dev.iredsc.hadron.api.event.block.BlockFertilizeEvent;
import dev.iredsc.hadron.api.event.block.BlockFormEvent;
import dev.iredsc.hadron.api.event.block.BlockFromToEvent;
import dev.iredsc.hadron.api.event.block.BlockGrowEvent;
import dev.iredsc.hadron.api.event.block.BlockSpreadEvent;
import dev.iredsc.hadron.api.event.block.LeavesDecayEvent;
import dev.iredsc.hadron.api.event.block.StructureGrowEvent;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CropBlock;

/**
 * The world changing on its own (fluids, grass, cactus, leaves) and growth a player causes (bone meal, trees). Random
 * ticks are sped up where a change waits on one; a player stands nearby, since only chunks near a player tick.
 */
public final class NaturalTests {
    // ---- Fluids

    @HadronTest
    void waterFlowingIsFromToByNature(TestContext t) {
        BlockPos source = t.pos(2, 0, 2);
        Fixtures.awaitTicking(t, source);
        List<BlockFromToEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockFromToEvent e) {
                seen.add(e);
            }
        });
        t.setBlock(source, Blocks.WATER);
        t.await("water to flow", () -> !seen.isEmpty(), 40);
        BlockFromToEvent flow = seen.get(0);
        assertNature(t, flow.getActor());
        t.assertEquals(source, flow.getFromPos(), "where the water flows from");
        t.assertEquals(source.relative(flow.getFace()), flow.getToPos(), "where the water flows to");
        t.tick(2);
        t.assertBlock(flow.getToPos(), Blocks.WATER);
    }

    @HadronTest
    void cancelledFlowMovesNoWater(TestContext t) {
        BlockPos source = t.pos(2, 0, 2);
        Fixtures.awaitTicking(t, source);
        List<BlockFromToEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockFromToEvent e) {
                seen.add(e);
                e.setCancelled(true);
            }
        });
        t.setBlock(source, Blocks.WATER);
        t.tick(30);
        t.assertFalse(seen.isEmpty(), "the water should have tried to flow");
        for (Direction side : Direction.Plane.HORIZONTAL) t.assertBlock(source.relative(side), Blocks.AIR);
    }

    @HadronTest
    void lavaMeetingWaterIsAFormByNature(TestContext t) {
        BlockPos water = t.pos(2, 0, 2);
        BlockPos lava = water.east();
        List<BlockFormEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockFormEvent e) {
                seen.add(e);
            }
        });
        t.setBlock(water, Blocks.WATER);
        t.setBlock(lava, Blocks.LAVA);
        t.assertBlock(lava, Blocks.OBSIDIAN);
        t.assertEquals(1, seen.size(), "the number of BlockFormEvents for lava meeting water");
        t.assertTrue(seen.get(0).getAfter().is(Blocks.OBSIDIAN), "the formed block should be obsidian, but was "
                + seen.get(0).getAfter());
        assertNature(t, seen.get(0).getActor());
    }

    @HadronTest
    void cancelledFormLeavesTheLava(TestContext t) {
        BlockPos water = t.pos(2, 0, 2);
        BlockPos lava = water.east();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockFormEvent e) {
                e.setCancelled(true);
            }
        });
        t.setBlock(water, Blocks.WATER);
        t.setBlock(lava, Blocks.LAVA);
        t.assertBlock(lava, Blocks.LAVA);
    }

    // ---- Grass, cactus and leaves, on random ticks

    /** Grass at pos(2, 0, 2), dirt beside it, and glowstone lighting both, whatever the time of day. */
    private static BlockPos grassBesideDirt(TestContext t) {
        BlockPos grass = t.pos(2, 0, 2);
        t.setBlock(grass, Blocks.GRASS_BLOCK);
        t.setBlock(grass.east(), Blocks.DIRT);
        t.setBlock(grass.above().south(), Blocks.GLOWSTONE);
        return grass;
    }

    @HadronTest(timeout = 900)
    void grassSpreadingToDirtIsASpreadByNature(TestContext t) {
        t.player("alice");
        BlockPos grass = grassBesideDirt(t);
        List<BlockSpreadEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockSpreadEvent e) {
                seen.add(e);
            }
        });
        withRandomTicks(t, () -> t.await("grass to spread", () -> t.getBlock(grass.east()).is(Blocks.GRASS_BLOCK),
                600));
        t.assertEquals(1, seen.size(), "the number of BlockSpreadEvents for grass spreading once");
        assertNature(t, seen.get(0).getActor());
        t.assertEquals(grass, seen.get(0).getSourcePos(), "where the grass spread from");
        t.assertEquals(grass.east(), seen.get(0).getPos(), "where the grass spread to");
    }

    @HadronTest(timeout = 900)
    void cancelledSpreadLeavesTheDirt(TestContext t) {
        t.player("alice");
        BlockPos grass = grassBesideDirt(t);
        List<BlockSpreadEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockSpreadEvent e) {
                seen.add(e);
                e.setCancelled(true);
            }
        });
        withRandomTicks(t, () -> t.await("grass to try spreading", () -> !seen.isEmpty(), 600));
        t.assertBlock(grass.east(), Blocks.DIRT);
    }

    @HadronTest(timeout = 900)
    void grassDyingUnderABlockIsAFadeByNature(TestContext t) {
        t.player("alice");
        BlockPos grass = t.pos(2, 0, 2);
        t.setBlock(grass, Blocks.GRASS_BLOCK);
        t.setBlock(grass.above(), Blocks.STONE);
        List<BlockFadeEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockFadeEvent e) {
                seen.add(e);
            }
        });
        withRandomTicks(t, () -> t.await("the grass to die", () -> t.getBlock(grass).is(Blocks.DIRT), 600));
        t.assertEquals(1, seen.size(), "the number of BlockFadeEvents for grass dying");
        assertNature(t, seen.get(0).getActor());
        t.assertTrue(seen.get(0).getNewState().is(Blocks.DIRT), "the grass should fade to dirt, but the event said "
                + seen.get(0).getNewState());
    }

    @HadronTest(timeout = 900)
    void cancelledFadeKeepsTheGrass(TestContext t) {
        t.player("alice");
        BlockPos grass = t.pos(2, 0, 2);
        t.setBlock(grass, Blocks.GRASS_BLOCK);
        t.setBlock(grass.above(), Blocks.STONE);
        List<BlockFadeEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockFadeEvent e) {
                seen.add(e);
                e.setCancelled(true);
            }
        });
        withRandomTicks(t, () -> t.await("the grass to try dying", () -> !seen.isEmpty(), 600));
        t.assertBlock(grass, Blocks.GRASS_BLOCK);
    }

    /** A cactus on sand at pos(3, 1, 3), nothing beside it. */
    private static BlockPos cactus(TestContext t) {
        BlockPos cactus = t.pos(3, 1, 3);
        t.setBlock(cactus.below(), Blocks.SAND);
        t.setBlock(cactus, Blocks.CACTUS);
        return cactus;
    }

    @HadronTest(timeout = 900)
    void cactusGrowingIsGrowByNature(TestContext t) {
        t.player("alice");
        BlockPos cactus = cactus(t);
        List<BlockGrowEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockGrowEvent e) {
                seen.add(e);
            }
        });
        withRandomTicks(t, () -> t.await("the cactus to grow taller", () -> t.getBlock(cactus.above())
                .is(Blocks.CACTUS), 600));
        t.assertFalse(seen.isEmpty(), "growing should fire BlockGrowEvents");
        for (BlockGrowEvent e : seen) assertNature(t, e.getActor());
        t.assertTrue(seen.stream().anyMatch(e -> e.getPos().equals(cactus.above())
                && e.getNewState().is(Blocks.CACTUS)), "one BlockGrowEvent should be the new cactus block above");
    }

    @HadronTest(timeout = 900)
    void cancelledGrowKeepsTheCactusAsItWas(TestContext t) {
        t.player("alice");
        BlockPos cactus = cactus(t);
        List<BlockGrowEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockGrowEvent e) {
                seen.add(e);
                e.setCancelled(true);
            }
        });
        withRandomTicks(t, () -> {
            t.await("the cactus to try growing", () -> !seen.isEmpty(), 600);
            t.tick(100);
        });
        t.assertBlock(cactus.above(), Blocks.AIR);
        t.assertEquals(0, t.getBlock(cactus).getValue(CactusBlock.AGE), "the cactus's age");
    }

    @HadronTest(timeout = 900)
    void leavesDecayingIsLeavesDecayByNature(TestContext t) {
        t.player("alice");
        BlockPos leaves = t.pos(3, 0, 3);
        List<LeavesDecayEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(LeavesDecayEvent e) {
                seen.add(e);
            }

            @EventHandler
            void on(BlockChangeEvent e) {
                if (!(e instanceof LeavesDecayEvent) && e.getPos().equals(leaves)) seen.add(null);
            }
        });
        t.setBlock(leaves, Blocks.OAK_LEAVES); // not persistent, no log nearby: it decays
        withRandomTicks(t, () -> t.await("the leaves to decay", () -> t.getBlock(leaves).isAir(), 600));
        t.assertEquals(1, seen.size(), "the number of block change events for one leaf decaying (null is another "
                + "kind of change reported for the same block)");
        assertNature(t, seen.get(0).getActor());
    }

    @HadronTest(timeout = 900)
    void cancelledDecayKeepsTheLeaves(TestContext t) {
        t.player("alice");
        BlockPos leaves = t.pos(3, 0, 3);
        List<LeavesDecayEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(LeavesDecayEvent e) {
                seen.add(e);
                e.setCancelled(true);
            }
        });
        t.setBlock(leaves, Blocks.OAK_LEAVES);
        withRandomTicks(t, () -> t.await("the leaves to try decaying", () -> !seen.isEmpty(), 600));
        t.tick(2);
        t.assertBlock(leaves, Blocks.OAK_LEAVES);
    }

    // ---- Bone meal and trees

    /** Wheat just planted on farmland at pos(1, 1, 0). */
    private static BlockPos wheat(TestContext t) {
        BlockPos crop = t.pos(1, 1, 0);
        t.setBlock(crop.below(), Blocks.FARMLAND);
        t.setBlock(crop, Blocks.WHEAT);
        return crop;
    }

    @HadronTest
    void boneMealIsOneFertilizeByThePlayer(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos crop = wheat(t);
        alice.hold(new ItemStack(Items.BONE_MEAL, 5));
        List<BlockChangeEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockChangeEvent e) {
                seen.add(e);
            }
        });
        alice.useBlock(crop);
        t.assertTrue(((CropBlock) Blocks.WHEAT).getAge(t.getBlock(crop)) > 0, "bone meal should have grown the "
                + "wheat");
        t.assertHolding(alice, Items.BONE_MEAL, 4);
        t.assertEquals(1, seen.size(), "the number of block change events for one bone meal (got "
                + seen.stream().map(e -> e.getClass().getSimpleName()).toList() + ")");
        t.assertTrue(seen.get(0) instanceof BlockFertilizeEvent, "bone meal should be BlockFertilizeEvent, but was "
                + seen.get(0).getClass().getSimpleName());
        assertPlayer(t, seen.get(0).getActor(), alice);
    }

    @HadronTest
    void cancelledFertilizeKeepsTheCropAndTheBoneMeal(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos crop = wheat(t);
        alice.hold(new ItemStack(Items.BONE_MEAL, 5));
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockFertilizeEvent e) {
                e.setCancelled(true);
            }
        });
        alice.useBlock(crop);
        t.tick();
        t.assertEquals(0, ((CropBlock) Blocks.WHEAT).getAge(t.getBlock(crop)), "the wheat's age");
        t.assertHolding(alice, Items.BONE_MEAL, 5);
    }

    /** An oak sapling on dirt at pos(2, 1, 2). */
    private static BlockPos sapling(TestContext t) {
        BlockPos sapling = t.pos(2, 1, 2);
        t.setBlock(sapling.below(), Blocks.DIRT);
        t.setBlock(sapling, Blocks.OAK_SAPLING);
        return sapling;
    }

    @HadronTest
    void aTreeFromBoneMealIsOneStructureGrowByThePlayer(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos sapling = sapling(t);
        alice.hold(new ItemStack(Items.BONE_MEAL, 64));
        List<StructureGrowEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(StructureGrowEvent e) {
                seen.add(e);
            }
        });
        // Each bone meal has a 45% chance to advance the sapling; the second advance grows the tree.
        for (int i = 0; i < 60 && t.getBlock(sapling).is(Blocks.OAK_SAPLING); i++) alice.useBlock(sapling);
        t.assertBlock(sapling, Blocks.OAK_LOG);
        t.assertEquals(1, seen.size(), "the number of StructureGrowEvents for one tree");
        StructureGrowEvent grow = seen.get(0);
        assertPlayer(t, grow.getActor(), alice);
        t.assertTrue(grow.isFromBonemeal(), "a tree grown by bone meal should say so");
        t.assertTrue(grow.getBlocks().size() > 4, "the tree's event should list its logs and leaves, but listed "
                + grow.getBlocks().size() + " blocks");
    }

    @HadronTest
    void cancelledStructureGrowLeavesTheSaplingAndNoTree(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos sapling = sapling(t);
        alice.hold(new ItemStack(Items.BONE_MEAL, 64));
        List<StructureGrowEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(StructureGrowEvent e) {
                seen.add(e);
                e.setCancelled(true);
            }
        });
        for (int i = 0; i < 60 && seen.isEmpty(); i++) alice.useBlock(sapling);
        t.assertFalse(seen.isEmpty(), "the sapling should have tried to grow within 60 bone meal");
        t.tick();
        t.assertBlock(sapling, Blocks.OAK_SAPLING);
        for (BlockPos pos : BlockPos.betweenClosed(sapling.offset(-3, 0, -3), sapling.offset(3, 8, 3))) {
            if (t.getBlock(pos).is(Blocks.OAK_LOG) || t.getBlock(pos).is(Blocks.OAK_LEAVES)) {
                t.fail("A cancelled tree should leave no logs or leaves, but " + t.getBlock(pos).getBlock()
                        + " is at " + t.describe(pos.immutable()));
            }
        }
    }
}
