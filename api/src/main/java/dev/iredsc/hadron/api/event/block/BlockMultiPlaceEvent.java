package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Several blocks are about to be placed by one action: both halves of a door or bed, or a building gadget's batch.
 * Every space is in {@link #getBlocks()}. Cancel it and none of them are placed. A {@link BlockPlaceEvent}, so a
 * listener for single places hears these too; {@link #getPos()} is the block the actor placed directly.
 */
public class BlockMultiPlaceEvent extends BlockPlaceEvent {
    public BlockMultiPlaceEvent(ServerLevel level, BlockPos pos, BlockState placed, List<BlockChange> blocks,
            Actor actor) {
        super(level, pos, placed, blocks, actor);
    }
}
