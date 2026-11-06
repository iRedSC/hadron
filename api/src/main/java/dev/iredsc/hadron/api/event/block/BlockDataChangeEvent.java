package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A block entity's data is about to change while its block state stays the same: a spawner's mob, a drawer's lock, a
 * terminal's binding, a custom name. Cancel it and the data stays as it was.
 *
 * <p><b>Fired by bridges</b>: vanilla has no single point where block entity data changes, so core doesn't fire it.
 * A bridge fires it before its mod writes the data, with the data before and after both filled in, and skips the
 * write if it is cancelled.
 */
public class BlockDataChangeEvent extends BlockChangeEvent {
    public BlockDataChangeEvent(ServerLevel level, BlockPos pos, BlockState state, CompoundTag before,
            CompoundTag after, Actor actor) {
        super(level, pos, state, List.of(BlockChange.withData(pos, state, state,
                Objects.requireNonNull(before, "before; pass the block entity's data as it is now"),
                Objects.requireNonNull(after, "after; pass the data as it will be"))), actor);
    }

    /** A copy of the data before. */
    public CompoundTag getBeforeData() {
        return primary().getBeforeData().orElseThrow();
    }

    /** A copy of the data after. */
    public CompoundTag getAfterData() {
        return primary().getAfterData().orElseThrow();
    }
}
