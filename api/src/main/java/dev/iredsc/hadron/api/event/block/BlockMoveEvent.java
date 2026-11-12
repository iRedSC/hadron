package dev.iredsc.hadron.api.event.block;

import java.util.ArrayList;
import dev.iredsc.hadron.api.event.Carried;
import dev.iredsc.hadron.api.event.Places;
import dev.iredsc.hadron.api.event.Place;
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
 * the move destroys. {@link #getCarried()} is every entity that goes along: a ship's crew, or {@link Carried.Unknown}
 * for a piston, which picks up entities as it travels. A protection plugin checks the swept space, each move's source
 * and target, and what it carries.
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
    private final Carried carried;
    private boolean cancelled;

    /**
     * @param pos what is moving them: the piston, the contraption's anchor
     * @param moves each block's source and target
     * @param swept every space passed through or landed in
     * @param blocks every space whose contents change, with before and after
     * @param carried every entity that moves along with the blocks ({@link Carried#of}, or {@link Carried#none()});
     *     {@link Carried#unknown} when the mod decides that only as it moves
     */
    public BlockMoveEvent(ServerLevel level, BlockPos pos, BlockState state, List<Move> moves, List<BlockPos> swept,
            List<BlockChange> blocks, Carried carried, Actor actor) {
        super(level, pos, state, blocks, actor);
        this.moves = List.copyOf(moves);
        this.swept = swept.stream().map(BlockPos::immutable).toList();
        this.carried = Objects.requireNonNull(carried, "carried; use Carried.none() when nothing goes along");
    }

    public List<Move> getMoves() {
        return moves;
    }

    public List<BlockPos> getSweptSpace() {
        return swept;
    }

    /** Every entity that moves along with the blocks, or {@link Carried.Unknown} when that can't be known yet. */
    public Carried getCarried() {
        return carried;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    /** Its own places, each move's source and target, every space swept, then where each carried entity is. */
    @Override
    public Places getPlaces() {
        List<Place> more = new ArrayList<>();
        for (Move move : getMoves()) {
            more.add(Place.Spot.at(getLevel(), move.from()));
            more.add(Place.Spot.at(getLevel(), move.to()));
        }
        for (BlockPos swept : getSweptSpace()) more.add(Place.Spot.at(getLevel(), swept));
        more.addAll(getCarried().places());
        return super.getPlaces().and(more);
    }
}
