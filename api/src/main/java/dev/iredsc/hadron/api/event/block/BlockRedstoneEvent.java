package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * An actor is about to change the redstone signal a block emits without changing its state: a logic network's
 * output, a wireless redstone transmitter. Change {@link #setNewCurrent} to emit something else; cancel it and the
 * signal stays at {@link #getOldCurrent()}.
 *
 * <p><b>Fired by bridges</b>: vanilla's signals always come from block states, which {@link BlockChangeEvent}
 * covers. A bridge fires it before its mod changes a signal kept outside the block state, applies
 * {@link #getNewCurrent()} afterwards, and keeps the old signal if it is cancelled.
 */
public class BlockRedstoneEvent extends BlockEvent implements Cancellable {
    private final int oldCurrent;
    private int newCurrent;
    private boolean cancelled;

    public BlockRedstoneEvent(ServerLevel level, BlockPos pos, BlockState state, int oldCurrent, int newCurrent,
            Actor actor) {
        super(level, pos, state, List.of(BlockChange.unchanged(level, pos, state)), actor);
        this.oldCurrent = checkCurrent(oldCurrent);
        this.newCurrent = checkCurrent(newCurrent);
    }

    public int getOldCurrent() {
        return oldCurrent;
    }

    public int getNewCurrent() {
        return newCurrent;
    }

    /** The signal to emit instead, from 0 to 15. */
    public void setNewCurrent(int newCurrent) {
        this.newCurrent = checkCurrent(newCurrent);
    }

    private static int checkCurrent(int current) {
        if (current < 0 || current > 15) {
            throw new IllegalArgumentException("A redstone signal is from 0 to 15, got " + current);
        }
        return current;
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
