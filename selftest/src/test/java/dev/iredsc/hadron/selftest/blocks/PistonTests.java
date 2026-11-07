package dev.iredsc.hadron.selftest.blocks;

import static dev.iredsc.hadron.selftest.blocks.Fixtures.assertNature;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.block.BlockMoveEvent;
import dev.iredsc.hadron.api.event.block.BlockPistonExtendEvent;
import dev.iredsc.hadron.api.event.block.BlockPistonRetractEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;

/** Pistons pushing and pulling, powered by redstone with nobody behind it. */
public final class PistonTests {
    /** A piston at pos(2, 0, 0) facing east, stone in front of it, nothing powering it yet. */
    private static BlockPos piston(TestContext t, Block type) {
        BlockPos piston = t.pos(2, 0, 0);
        Fixtures.awaitTicking(t, piston);
        t.setBlock(piston, type.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
        t.setBlock(piston.east(), Blocks.STONE);
        return piston;
    }

    private static void power(TestContext t, BlockPos piston, boolean on) {
        t.setBlock(piston.west(), on ? Blocks.REDSTONE_BLOCK : Blocks.AIR);
        t.tick(2);
        t.await("the piston to finish moving", () -> !t.getBlock(piston.east()).is(Blocks.MOVING_PISTON)
                && !t.getBlock(piston.east(2)).is(Blocks.MOVING_PISTON), 20);
    }

    private static List<BlockMoveEvent> recordMoves(TestContext t) {
        List<BlockMoveEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockMoveEvent e) {
                seen.add(e);
            }
        });
        return seen;
    }

    @HadronTest
    @Covers(BlockPistonExtendEvent.class)
    void aPistonPushIsOneExtendEventWithTheMove(TestContext t) {
        BlockPos piston = piston(t, Blocks.PISTON);
        List<BlockMoveEvent> seen = recordMoves(t);
        power(t, piston, true);
        t.assertBlock(piston.east(2), Blocks.STONE);
        t.assertBlock(piston.east(), Blocks.PISTON_HEAD);
        t.assertEquals(1, seen.size(), "the number of block move events for one push");
        t.assertTrue(seen.get(0) instanceof BlockPistonExtendEvent, "a push should be BlockPistonExtendEvent, but "
                + "was " + seen.get(0).getClass().getSimpleName());
        List<BlockMoveEvent.Move> moves = seen.get(0).getMoves();
        t.assertEquals(1, moves.size(), "the number of blocks the push moves");
        t.assertEquals(piston.east(), moves.get(0).from(), "where the stone moves from");
        t.assertEquals(piston.east(2), moves.get(0).to(), "where the stone moves to");
        assertNature(t, seen.get(0).getActor());
    }

    @HadronTest
    @Covers(BlockPistonExtendEvent.class)
    void cancelledPistonPushMovesNothing(TestContext t) {
        BlockPos piston = piston(t, Blocks.PISTON);
        List<BlockPistonExtendEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockPistonExtendEvent e) {
                seen.add(e);
                e.setCancelled(true);
            }
        });
        power(t, piston, true);
        t.assertFalse(seen.isEmpty(), "the piston should have tried to push");
        t.assertBlock(piston.east(), Blocks.STONE);
        t.assertBlock(piston.east(2), Blocks.AIR);
        t.assertFalse(t.getBlock(piston).getValue(PistonBaseBlock.EXTENDED), "the piston should not be extended");
    }

    @HadronTest
    @Covers(BlockPistonRetractEvent.class)
    void aStickyPistonPullIsOneRetractEvent(TestContext t) {
        BlockPos piston = piston(t, Blocks.STICKY_PISTON);
        power(t, piston, true);
        t.assertBlock(piston.east(2), Blocks.STONE);
        List<BlockMoveEvent> seen = recordMoves(t);
        power(t, piston, false);
        t.assertBlock(piston.east(), Blocks.STONE);
        t.assertBlock(piston.east(2), Blocks.AIR);
        t.assertEquals(1, seen.size(), "the number of block move events for one pull");
        t.assertTrue(seen.get(0) instanceof BlockPistonRetractEvent, "a pull should be BlockPistonRetractEvent, "
                + "but was " + seen.get(0).getClass().getSimpleName());
        t.assertTrue(((BlockPistonRetractEvent) seen.get(0)).isSticky(), "the retract should say it is sticky");
        List<BlockMoveEvent.Move> moves = seen.get(0).getMoves();
        t.assertEquals(1, moves.size(), "the number of blocks the pull moves");
        t.assertEquals(piston.east(2), moves.get(0).from(), "where the stone is pulled from");
        t.assertEquals(piston.east(), moves.get(0).to(), "where the stone is pulled to");
    }

    @HadronTest
    @Covers(BlockPistonRetractEvent.class)
    void cancelledPistonRetractPullsNothing(TestContext t) {
        BlockPos piston = piston(t, Blocks.STICKY_PISTON);
        power(t, piston, true);
        t.assertBlock(piston.east(2), Blocks.STONE);
        List<BlockPistonRetractEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockPistonRetractEvent e) {
                seen.add(e);
                e.setCancelled(true);
            }
        });
        power(t, piston, false);
        t.assertFalse(seen.isEmpty(), "the piston should have tried to pull");
        t.assertBlock(piston.east(2), Blocks.STONE);
        t.assertBlock(piston.east(), Blocks.PISTON_HEAD);
    }
}
