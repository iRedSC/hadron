package dev.iredsc.hadron.api.event.block;

import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A fluid is about to flow from one block into the next. Cancel it and it doesn't flow there.
 *
 * <p>Unlike Bukkit, {@link #getPos()} is the space being flowed into, the one that changes, so a protection plugin
 * checking {@code getPos()} protects the right block; {@link #getFromPos()} is where the fluid comes from.
 */
@FiredBy(core = FULL)
public class BlockFromToEvent extends BlockChangeEvent {
    private final BlockPos from;
    private final Direction face;

    public BlockFromToEvent(ServerLevel level, BlockPos from, Direction face, BlockState before, BlockState after,
            Actor actor) {
        this(level, from, face, BlockChange.of(level, from.relative(face), before, after), actor);
    }

    private BlockFromToEvent(ServerLevel level, BlockPos from, Direction face, BlockChange change, Actor actor) {
        super(level, change.getPos(), change.getAfter(), List.of(change), actor);
        this.from = from.immutable();
        this.face = face;
    }

    /** Where the fluid flows from. */
    public BlockPos getFromPos() {
        return from;
    }

    /** Where the fluid flows to; same as {@link #getPos()}. */
    public BlockPos getToPos() {
        return getPos();
    }

    /** The direction of the flow, from {@link #getFromPos()} to {@link #getToPos()}. */
    public Direction getFace() {
        return face;
    }
}
