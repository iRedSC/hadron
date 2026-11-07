package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * An actor is about to turn a block into a different block in place: stripping a log, tilling dirt, a machine
 * converting a block, a chest upgraded in place. Cancel it and the block stays as it was, and a tool used keeps its
 * durability.
 */
@FiredBy(core = FULL)
public class BlockTransformEvent extends BlockChangeEvent {
    public BlockTransformEvent(ServerLevel level, BlockPos pos, BlockState before, BlockState after, Actor actor) {
        super(level, pos, before, after, actor);
    }

    public BlockTransformEvent(ServerLevel level, BlockChange change, Actor actor) {
        super(level, change, actor);
    }
}
