package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A piston is about to retract; a sticky piston pulls {@link #getMoves()} with it, a plain one moves nothing but its
 * head. Cancel it and it stays extended.
 */
public class BlockPistonRetractEvent extends BlockPistonEvent {
    public BlockPistonRetractEvent(ServerLevel level, BlockPos pos, BlockState piston, Direction direction,
            boolean sticky, List<Move> moves, List<BlockPos> swept, List<BlockChange> blocks, Actor actor) {
        super(level, pos, piston, direction, sticky, moves, swept, blocks, actor);
    }
}
