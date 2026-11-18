package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Carried;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A piston is about to extend or retract. {@link #getPos()} is the piston; {@link #getMoves()} the blocks it pushes or
 * pulls. Cancel it and the piston doesn't move.
 *
 * <p>{@link #getCarried()} is {@link Carried.Unknown} ({@link Carried.Unknown.Reason#DECIDED_AS_IT_MOVES}), with no
 * bound: a piston moves the entities it bumps into, and those stuck to its slime or honey, on each tick of its travel,
 * after this event. Each one it moves into another block is asked about then, in an {@code EntityMoveEvent} (a
 * {@code PlayerMoveEvent} for a player) whose actor is the piston, with its passengers as rides; refused, that entity
 * is put back and the piston's blocks finish moving.
 */
public abstract class BlockPistonEvent extends BlockMoveEvent {
    private final Direction direction;
    private final boolean sticky;

    protected BlockPistonEvent(ServerLevel level, BlockPos pos, BlockState piston, Direction direction,
            boolean sticky, List<Move> moves, List<BlockPos> swept, List<BlockChange> blocks, Actor actor) {
        super(level, pos, piston, moves, swept, blocks, Carried.unknown(Carried.Unknown.Reason.DECIDED_AS_IT_MOVES),
                actor);
        this.direction = direction;
        this.sticky = sticky;
    }

    /** The way the piston faces. */
    public Direction getDirection() {
        return direction;
    }

    public boolean isSticky() {
        return sticky;
    }
}
