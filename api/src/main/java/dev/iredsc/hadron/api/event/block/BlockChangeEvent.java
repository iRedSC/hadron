package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A block's state or its block entity's data is about to change, from {@link #getBefore()} to {@link #getAfter()}.
 * Cancel it and nothing in {@link #getBlocks()} changes.
 *
 * <p>Fired as it is when an actor changes a block in a way no subclass names, such as opening a door or a machine
 * switching itself on. Listen here to hear every change, including breaks, places, burns, flows and growth.
 */
@FiredBy(core = FULL)
public class BlockChangeEvent extends BlockEvent implements Cancellable {
    private boolean cancelled;

    public BlockChangeEvent(ServerLevel level, BlockPos pos, BlockState before, BlockState after, Actor actor) {
        this(level, BlockChange.of(level, pos, before, after), actor);
    }

    public BlockChangeEvent(ServerLevel level, BlockChange change, Actor actor) {
        this(level, change.getPos(), change.getAfter(), List.of(change), actor);
    }

    /** For changes spanning several spaces; {@code about} is the block at {@code pos} the event is about. */
    protected BlockChangeEvent(ServerLevel level, BlockPos pos, BlockState about, List<BlockChange> blocks,
            Actor actor) {
        super(level, pos, about, blocks, actor);
    }

    /** The state at {@link #getPos()} before the change. */
    public BlockState getBefore() {
        return primary().getBefore();
    }

    /** The state at {@link #getPos()} after the change. */
    public BlockState getAfter() {
        return primary().getAfter();
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
