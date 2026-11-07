package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A block or fluid source is about to be placed by an actor: a player, or a machine, fake player or mob. Cancel it
 * and it isn't placed, and an item used to place it is given back. {@link #getState()} is the block being placed;
 * {@link #getBefore()} is the block it replaces.
 */
@FiredBy(core = FULL)
public class BlockPlaceEvent extends BlockChangeEvent {
    public BlockPlaceEvent(ServerLevel level, BlockPos pos, BlockState replaced, BlockState placed, Actor actor) {
        this(level, BlockChange.of(level, pos, replaced, placed), actor);
    }

    public BlockPlaceEvent(ServerLevel level, BlockChange change, Actor actor) {
        super(level, change.getPos(), change.getAfter(), List.of(change), actor);
    }

    protected BlockPlaceEvent(ServerLevel level, BlockPos pos, BlockState placed, List<BlockChange> blocks,
            Actor actor) {
        super(level, pos, placed, blocks, actor);
    }

    /** The block that was in the space before; same as {@link #getBefore()}. */
    public BlockState getBlockReplacedState() {
        return getBefore();
    }
}
