package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import static dev.iredsc.hadron.api.event.FiredBy.Core.PARTIAL;

/**
 * Blocks are about to move to new spaces, taking their block entity data and scheduled ticks with them: a piston, a
 * contraption, a ship. Cancel it and nothing moves.
 *
 * <p>{@link #getMoves()} maps each block from where it is to where it goes; {@link #getSweptSpace()} is every space
 * the move passes through or lands in; {@link #getBlocks()} is every space whose contents change, including blocks
 * the move destroys. A protection plugin checks the swept space and each move's source and target.
 */
@FiredBy(core = PARTIAL,
        coreSees = "pistons, through its piston leaves",
        bridges = "contraptions and ships within one level, as `StructureMoveEvent`")
public class BlockMoveEvent extends BlockEvent implements Cancellable {
    /** One block moving from {@code from} to {@code to}; {@code state} is the block as it is before moving. */
    public record Move(BlockPos from, BlockPos to, BlockState state) {
        public Move {
            from = Objects.requireNonNull(from, "from").immutable();
            to = Objects.requireNonNull(to, "to").immutable();
            Objects.requireNonNull(state, "state");
        }
    }

    private final List<Move> moves;
    private final List<BlockPos> swept;
    private boolean cancelled;

    /**
     * @param pos what is moving them: the piston, the contraption's anchor
     * @param moves each block's source and target
     * @param swept every space passed through or landed in
     * @param blocks every space whose contents change, with before and after
     */
    public BlockMoveEvent(ServerLevel level, BlockPos pos, BlockState state, List<Move> moves, List<BlockPos> swept,
            List<BlockChange> blocks, Actor actor) {
        super(level, pos, state, blocks, actor);
        this.moves = List.copyOf(moves);
        this.swept = swept.stream().map(BlockPos::immutable).toList();
    }

    public List<Move> getMoves() {
        return moves;
    }

    public List<BlockPos> getSweptSpace() {
        return swept;
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
