package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Carried;
import dev.iredsc.hadron.api.event.Place;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * A piston is about to extend or retract. {@link #getPos()} is the piston; {@link #getMoves()} the blocks it pushes or
 * pulls. Cancel it and the piston doesn't move.
 *
 * <p>{@link #getCarried()} is {@link Carried.Unknown} ({@link Carried.Unknown.Reason#DECIDED_AS_IT_MOVES}): a piston
 * moves the entities it bumps into, and those stuck to its slime or honey, on each tick of its travel, after this
 * event, so which ones can't be named here. Its bound is every space moved or swept grown by a block, so protection
 * can deny it only where that reaches.
 */
public abstract class BlockPistonEvent extends BlockMoveEvent {
    private final Direction direction;
    private final boolean sticky;

    protected BlockPistonEvent(ServerLevel level, BlockPos pos, BlockState piston, Direction direction,
            boolean sticky, List<Move> moves, List<BlockPos> swept, List<BlockChange> blocks, Actor actor) {
        super(level, pos, piston, moves, swept, blocks, reach(level, pos, moves, swept), actor);
        this.direction = direction;
        this.sticky = sticky;
    }

    /**
     * What a piston carries: unknown, within every space it moves or sweeps and the blocks beside them, where anything
     * it bumps into or that sticks to its slime stands, before and after.
     */
    private static Carried.Unknown reach(ServerLevel level, BlockPos pos, List<Move> moves, List<BlockPos> swept) {
        List<BlockPos> spaces = new ArrayList<>(swept);
        for (Move m : moves) {
            spaces.add(m.from());
            spaces.add(m.to());
        }
        BoundingBox box = BoundingBox.encapsulatingPositions(spaces).orElseGet(() -> new BoundingBox(pos));
        return Carried.unknown(new Place.Spot(level, box.inflatedBy(1)), Carried.Unknown.Reason.DECIDED_AS_IT_MOVES);
    }

    /** The way the piston faces. */
    public Direction getDirection() {
        return direction;
    }

    public boolean isSticky() {
        return sticky;
    }
}
