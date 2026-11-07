package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A piston is about to retract; a sticky piston pulls {@link #getMoves()} with it, a plain one moves nothing but its
 * head. Cancel it and it stays extended.
 */
@FiredBy(core = FULL)
public class BlockPistonRetractEvent extends BlockPistonEvent {
    public BlockPistonRetractEvent(ServerLevel level, BlockPos pos, BlockState piston, Direction direction,
            boolean sticky, List<Move> moves, List<BlockPos> swept, List<BlockChange> blocks, Actor actor) {
        super(level, pos, piston, direction, sticky, moves, swept, blocks, actor);
    }
}
