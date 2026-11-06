package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A piston is about to extend or retract. {@link #getPos()} is the piston; {@link #getMoves()} the blocks it pushes or
 * pulls. Cancel it and the piston doesn't move.
 */
public abstract class BlockPistonEvent extends BlockMoveEvent {
    private final Direction direction;
    private final boolean sticky;

    protected BlockPistonEvent(ServerLevel level, BlockPos pos, BlockState piston, Direction direction,
            boolean sticky, List<Move> moves, List<BlockPos> swept, List<BlockChange> blocks, Actor actor) {
        super(level, pos, piston, moves, swept, blocks, actor);
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
