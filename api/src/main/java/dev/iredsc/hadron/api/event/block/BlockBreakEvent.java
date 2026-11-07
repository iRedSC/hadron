package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A block is about to be removed by an actor: a player mining it, or a machine, fake player or mob breaking it, or a
 * fluid source being drained. Cancel it and the block stays, with nothing dropped. {@link #getState()} is the block
 * being broken; {@link #getAfter()} is what is left, usually air or the fluid that was in it.
 */
public class BlockBreakEvent extends BlockChangeEvent {
    public BlockBreakEvent(ServerLevel level, BlockPos pos, BlockState state, BlockState after, Actor actor) {
        this(level, BlockChange.of(level, pos, state, after), actor);
    }

    public BlockBreakEvent(ServerLevel level, BlockChange change, Actor actor) {
        super(level, change.getPos(), change.getBefore(), List.of(change), actor);
    }
}
