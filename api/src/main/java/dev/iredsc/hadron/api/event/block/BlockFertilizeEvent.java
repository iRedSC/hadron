package dev.iredsc.hadron.api.event.block;

import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import static dev.iredsc.hadron.api.event.FiredBy.Core.PARTIAL;

/**
 * Bone meal has grown blocks, by a player, a dispenser or anything else using it. Every space it changed is in
 * {@link #getBlocks()}. Cancel it and every one goes back as it was, and the bone meal isn't used up. A tree or giant
 * mushroom grown by bone meal is a {@link StructureGrowEvent} instead.
 *
 * <p>A mod's growth accelerator that grows blocks without bone meal is reported by its bridge, as this event.
 */
@FiredBy(core = PARTIAL, coreSees = "bone meal", bridges = "growth accelerators that grow without bone meal")
public class BlockFertilizeEvent extends BlockChangeEvent {
    public BlockFertilizeEvent(ServerLevel level, BlockPos pos, BlockState state, List<BlockChange> blocks,
            Actor actor) {
        super(level, pos, state, blocks, actor);
    }
}
