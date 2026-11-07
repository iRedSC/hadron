package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Bone meal has grown blocks, by a player, a dispenser or anything else using it. Every space it changed is in
 * {@link #getBlocks()}. Cancel it and every one goes back as it was, and the bone meal isn't used up. A tree or giant
 * mushroom grown by bone meal is a {@link StructureGrowEvent} instead.
 *
 * <p>A mod's growth accelerator that grows blocks without bone meal is reported by its bridge, as this event.
 */
public class BlockFertilizeEvent extends BlockChangeEvent {
    public BlockFertilizeEvent(ServerLevel level, BlockPos pos, BlockState state, List<BlockChange> blocks,
            Actor actor) {
        super(level, pos, state, blocks, actor);
    }
}
