package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A block is being removed by an actor: a player mining it, or a machine, fake player or mob. Cancel it and the block
 * stays. {@link #getState()} is the block being broken.
 */
public class BlockBreakEvent extends BlockChangeEvent {
    public BlockBreakEvent(ServerLevel level, BlockPos pos, BlockState state, BlockState after, Actor actor) {
        super(level, pos, state, after, state, actor);
    }
}
