package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** A block's state is about to change, from {@link #getBefore()} to {@link #getAfter()}. Cancel it and it doesn't. */
public class BlockChangeEvent extends BlockEvent implements Cancellable {
    private final BlockState before;
    private final BlockState after;
    private boolean cancelled;

    public BlockChangeEvent(ServerLevel level, BlockPos pos, BlockState before, BlockState after, Actor actor) {
        this(level, pos, before, after, after, actor);
    }

    protected BlockChangeEvent(ServerLevel level, BlockPos pos, BlockState before, BlockState after, BlockState about,
            Actor actor) {
        super(level, pos, about, actor);
        this.before = before;
        this.after = after;
    }

    public BlockState getBefore() {
        return before;
    }

    public BlockState getAfter() {
        return after;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}
