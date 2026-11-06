package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A block is being placed by an actor: a player, or a machine, fake player or mob. Cancel it and it isn't placed.
 * {@link #getState()} is the block being placed.
 */
public class BlockPlaceEvent extends BlockChangeEvent {
    public BlockPlaceEvent(ServerLevel level, BlockPos pos, BlockState replaced, BlockState placed, Actor actor) {
        super(level, pos, replaced, placed, placed, actor);
    }
}
