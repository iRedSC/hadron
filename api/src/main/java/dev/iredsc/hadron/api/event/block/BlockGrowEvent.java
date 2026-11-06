package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A block grows on its own: a crop ages, sugar cane or cactus grows taller, a stem grows its fruit. Cancel it and it
 * doesn't grow this time. {@link #getAfter()} is the new state. Forming and spreading are subclasses, as in Bukkit.
 */
public class BlockGrowEvent extends BlockChangeEvent {
    public BlockGrowEvent(ServerLevel level, BlockPos pos, BlockState before, BlockState after, Actor actor) {
        super(level, pos, before, after, actor);
    }

    public BlockGrowEvent(ServerLevel level, BlockChange change, Actor actor) {
        super(level, change, actor);
    }

    /** The state after growing; same as {@link #getAfter()}. */
    public BlockState getNewState() {
        return getAfter();
    }
}
