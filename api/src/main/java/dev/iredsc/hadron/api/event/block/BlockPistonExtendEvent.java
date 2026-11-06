package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** A piston is about to extend, pushing {@link #getMoves()} and breaking what it can't push. Cancel it and it doesn't. */
public class BlockPistonExtendEvent extends BlockPistonEvent {
    public BlockPistonExtendEvent(ServerLevel level, BlockPos pos, BlockState piston, Direction direction,
            boolean sticky, List<Move> moves, List<BlockPos> swept, List<BlockChange> blocks, Actor actor) {
        super(level, pos, piston, direction, sticky, moves, swept, blocks, actor);
    }
}
