package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;

import static dev.iredsc.hadron.api.event.FiredBy.Core.NONE;

/**
 * A group of blocks moves as one: a Create contraption, a Valkyrien Skies ship, an AE2 spatial transfer. Fired when it
 * assembles out of the world, each time it moves or rotates, and when it disassembles back into blocks. Cancel it and
 * that step doesn't happen.
 *
 * <p><b>Fired by bridges</b>: vanilla has nothing that moves a group of blocks this way, so core doesn't fire it. A
 * bridge fires it before its mod changes anything, with the transforms before and after, the blocks leaving or
 * entering the world as {@link #getMoves()}, and every space the structure passes through or lands in as
 * {@link #getSweptSpace()}; while it only moves, with no blocks entering or leaving, moves may be empty.
 *
 * <p>A move between levels (a teleposer, AE2's spatial storage) is two of these in one operation: {@code ASSEMBLE} in
 * the source level and {@code DISASSEMBLE} in the target, each with no moves and only its own level's spaces in
 * {@link #getBlocks()}, so no position is read in the wrong level. The bridge fires both before changing anything
 * and moves nothing if either is cancelled.
 */
@FiredBy(core = NONE,
        bridges = "assembles, moves, rotates or disassembles a contraption, ship or spatial transfer",
        fillIn = "source to target mapping, swept space, old and new transforms")
public class StructureMoveEvent extends BlockMoveEvent {
    /** Which step of the structure's life this is. */
    public enum Phase {
        /** Blocks leave the world to become the structure. */
        ASSEMBLE,
        /** The structure moves or rotates. */
        MOVE,
        /** The structure turns back into blocks in the world. */
        DISASSEMBLE
    }

    private final Phase phase;
    private final Matrix4dc from;
    private final Matrix4dc to;

    /**
     * @param from the structure's transform from its own block grid into the world, before this step
     * @param to the same transform after this step
     */
    public StructureMoveEvent(ServerLevel level, BlockPos pos, BlockState state, Phase phase, Matrix4dc from,
            Matrix4dc to, List<Move> moves, List<BlockPos> swept, List<BlockChange> blocks, Actor actor) {
        super(level, pos, state, moves, swept, blocks, actor);
        this.phase = Objects.requireNonNull(phase, "phase");
        this.from = new Matrix4d(Objects.requireNonNull(from, "from; use new Matrix4d() for the identity"));
        this.to = new Matrix4d(Objects.requireNonNull(to, "to; use new Matrix4d() for the identity"));
    }

    public Phase getPhase() {
        return phase;
    }

    /** The structure's transform into the world before this step. */
    public Matrix4dc getFrom() {
        return from;
    }

    /** The structure's transform into the world after this step. */
    public Matrix4dc getTo() {
        return to;
    }
}
