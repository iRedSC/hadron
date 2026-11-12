package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Carried;
import dev.iredsc.hadron.api.event.Place;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

/**
 * A piston is about to extend or retract. {@link #getPos()} is the piston; {@link #getMoves()} the blocks it pushes or
 * pulls. Cancel it and the piston doesn't move.
 *
 * <p>{@link #getCarried()} is {@link Carried.Unknown} ({@link Carried.Unknown.Reason#DECIDED_AS_IT_MOVES}): a piston
 * moves the entities it bumps into, and those stuck to its slime or honey, on each tick of its travel, after this
 * event, so which ones can't be named here. Its bound is built from boxes: every space moved or swept grown by a
 * block, the whole box of each entity reaching that space grown by a block, and the space grown down by the tallest of
 * them, so protection can deny it only where that reaches.
 */
public abstract class BlockPistonEvent extends BlockMoveEvent {
    private final Direction direction;
    private final boolean sticky;

    protected BlockPistonEvent(ServerLevel level, BlockPos pos, BlockState piston, Direction direction,
            boolean sticky, List<Move> moves, List<BlockPos> swept, List<BlockChange> blocks, Actor actor) {
        super(level, pos, piston, moves, swept, blocks, reach(level, pos, moves, swept), actor);
        this.direction = direction;
        this.sticky = sticky;
    }

    /**
     * What a piston carries: unknown, but within this bound, built from boxes. The spaces it moves or sweeps, grown by
     * a block, hold anything it bumps into or that sticks to its slime; each entity whose box reaches them now is held
     * whole, grown by the block it can be pushed, so a tall mob's feet below the push are inside; and the space grows
     * down by the tallest of them, for one that steps in while the piston travels. Wide is safe; narrow would be a hole.
     */
    private static Carried.Unknown reach(ServerLevel level, BlockPos pos, List<Move> moves, List<BlockPos> swept) {
        List<BlockPos> spaces = new ArrayList<>(swept);
        for (Move m : moves) {
            spaces.add(m.from());
            spaces.add(m.to());
        }
        BoundingBox blocks = BoundingBox.encapsulatingPositions(spaces).orElseGet(() -> new BoundingBox(pos))
                .inflatedBy(1);
        AABB near = AABB.of(blocks);
        double minX = near.minX, minY = near.minY, minZ = near.minZ;
        double maxX = near.maxX, maxY = near.maxY, maxZ = near.maxZ;
        double tallest = 0;
        for (Entity e : level.getEntities((Entity) null, near, e -> true)) {
            AABB box = e.getBoundingBox().inflate(1);
            minX = Math.min(minX, box.minX);
            minY = Math.min(minY, box.minY);
            minZ = Math.min(minZ, box.minZ);
            maxX = Math.max(maxX, box.maxX);
            maxY = Math.max(maxY, box.maxY);
            maxZ = Math.max(maxZ, box.maxZ);
            tallest = Math.max(tallest, e.getBbHeight());
        }
        minY = Math.min(minY, near.minY - Math.ceil(tallest));
        BoundingBox bound = BoundingBox.fromCorners(BlockPos.containing(minX, minY, minZ),
                BlockPos.containing(maxX - 1.0E-7, maxY - 1.0E-7, maxZ - 1.0E-7));
        return Carried.unknown(new Place.Spot(level, bound), Carried.Unknown.Reason.DECIDED_AS_IT_MOVES);
    }

    /** The way the piston faces. */
    public Direction getDirection() {
        return direction;
    }

    public boolean isSticky() {
        return sticky;
    }
}
