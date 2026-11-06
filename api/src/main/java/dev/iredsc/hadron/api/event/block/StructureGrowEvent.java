package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A tree, giant mushroom or huge fungus has grown from one block, on its own or from bone meal. Every block it placed
 * is in {@link #getBlocks()}; {@link #getPos()} is the sapling or mushroom it grew from. Cancel it and every block goes
 * back as it was.
 */
public class StructureGrowEvent extends BlockChangeEvent {
    private final boolean bonemeal;

    public StructureGrowEvent(ServerLevel level, BlockPos pos, BlockState sapling, List<BlockChange> blocks,
            boolean bonemeal, Actor actor) {
        super(level, pos, sapling, blocks, actor);
        this.bonemeal = bonemeal;
    }

    /** Whether bone meal made it grow, rather than time. */
    public boolean isFromBonemeal() {
        return bonemeal;
    }
}
