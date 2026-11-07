package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A block spreads from one space into another: grass or mycelium onto dirt, vines, mushrooms. Cancel it and it
 * doesn't spread there. {@link #getPos()} is the space spread into; {@link #getSourcePos()} where it came from.
 */
@FiredBy(core = FULL)
public class BlockSpreadEvent extends BlockFormEvent {
    private final BlockPos source;

    public BlockSpreadEvent(ServerLevel level, BlockPos pos, BlockPos source, BlockState before, BlockState after,
            Actor actor) {
        super(level, pos, before, after, actor);
        this.source = source.immutable();
    }

    /** The block it spreads from. */
    public BlockPos getSourcePos() {
        return source;
    }
}
