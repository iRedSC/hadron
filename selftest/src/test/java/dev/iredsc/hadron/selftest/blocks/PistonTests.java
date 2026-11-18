package dev.iredsc.hadron.selftest.blocks;

import static dev.iredsc.hadron.selftest.blocks.Fixtures.assertNature;

import dev.iredsc.hadron.api.event.Carried;
import dev.iredsc.hadron.api.event.entity.EntityMoveEvent;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.block.BlockMoveEvent;
import dev.iredsc.hadron.api.event.block.BlockPistonExtendEvent;
import dev.iredsc.hadron.api.event.block.BlockPistonEvent;
import dev.iredsc.hadron.api.event.block.BlockPistonRetractEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.EntityType;
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

    /** Every EntityMoveEvent heard from now on; refusing the ones {@code refuse} picks. */
    private static List<EntityMoveEvent> recordPushes(TestContext t, java.util.function.Predicate<EntityMoveEvent> refuse) {
        List<EntityMoveEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityMoveEvent e) {
                seen.add(e);
                if (refuse.test(e)) e.setCancelled(true);
            }
        });
        return seen;
    }

    private static boolean byPiston(EntityMoveEvent e, BlockPos movingBlock) {
        return e.getActor() instanceof Actor.Machine m && m.pos().equals(movingBlock);
    }

    /**
     * A piston picks up what it bumps into and what sticks to its honey as it travels, after its block move: that
     * event's carriage is unknown, never an empty list that reads as nothing, and has no bound; each entity it moves
     * into another block is asked about as it moves, in its own move naming the piston.
     */
    @HadronTest
    @Covers(BlockPistonExtendEvent.class)
    @Covers(BlockPistonRetractEvent.class)
    @Covers(EntityMoveEvent.class)
    void whatAPistonCarriesIsUnknownAndEachEntityItMovesIsAsked(TestContext t) {
        t.player("alice"); // the pig only ticks near a player
        BlockPos piston = piston(t, Blocks.STICKY_PISTON);
        t.setBlock(piston.east(), Blocks.HONEY_BLOCK);
        Pig pig = dev.iredsc.hadron.selftest.entities.Fixtures.spawn(t, EntityType.PIG, piston.east().above());
        pig.setOnGround(true); // a still pig never settles itself; honey carries only what stands on it
        List<BlockMoveEvent> seen = recordMoves(t);
        List<EntityMoveEvent> pushes = recordPushes(t, e -> false);
        power(t, piston, true);
        power(t, piston, false);
        t.assertEquals(2, seen.size(), "the push, then the pull");
        for (BlockMoveEvent e : seen) {
            t.assertTrue(e instanceof BlockPistonEvent, "a piston's move, but was " + e.getEventName());
            t.assertEquals(Carried.unknown(Carried.Unknown.Reason.DECIDED_AS_IT_MOVES), e.getCarried(),
                    e.getEventName() + "'s carriage");
            t.assertTrue(e.getPlaces().stream().noneMatch(p -> p instanceof Place.Unknown),
                    e.getEventName() + "'s places should name only its blocks: " + e.getPlaces());
        }
        t.assertTrue(pushes.stream().anyMatch(e -> e.getEntity() == pig && byPiston(e, piston.east(2))),
                "the pig carried on the honey should be asked about, by the piston, but heard " + pushes);
    }

    /** A pushed pig refused: the piston's blocks finish moving, the pig stays where it stood. */
    @HadronTest
    @Covers(EntityMoveEvent.class)
    void aRefusedPushLeavesTheEntityWhereItStood(TestContext t) {
        t.player("alice");
        BlockPos piston = piston(t, Blocks.STICKY_PISTON);
        t.setBlock(piston.east(), Blocks.HONEY_BLOCK);
        Pig pig = dev.iredsc.hadron.selftest.entities.Fixtures.spawn(t, EntityType.PIG, piston.east().above());
        pig.setOnGround(true); // a still pig never settles itself; honey carries only what stands on it
        t.tick();
        BlockPos stood = pig.blockPosition();
        List<EntityMoveEvent> refused = recordPushes(t, e -> e.getEntity() == pig);
        power(t, piston, true);
        t.assertTrue(!refused.isEmpty(), "the pig's push should have been asked about; it stood at " + stood
                + " and is at " + pig.position() + ", on the ground: " + pig.onGround());
        t.assertEquals(stood, pig.blockPosition(), "the pig's block, its push refused");
        t.assertBlock(piston.east(2), Blocks.HONEY_BLOCK);
    }
}
