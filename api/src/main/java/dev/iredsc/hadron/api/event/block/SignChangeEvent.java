package dev.iredsc.hadron.api.event.block;

import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A player is about to set a sign's text. Change lines with {@link #setLine}; cancel it and the sign keeps its old
 * text, on the player's screen too.
 */
@FiredBy(core = FULL)
public class SignChangeEvent extends BlockEvent implements Cancellable {
    private final List<String> lines;
    private final boolean front;
    private boolean changed;
    private boolean cancelled;

    public SignChangeEvent(ServerLevel level, BlockPos pos, BlockState state, List<String> lines, boolean front,
            Actor actor) {
        super(level, pos, state, List.of(BlockChange.unchanged(level, pos, state)), actor);
        this.lines = new ArrayList<>(lines);
        this.front = front;
    }

    /** The new lines, top to bottom. */
    public List<String> getLines() {
        return List.copyOf(lines);
    }

    public String getLine(int index) {
        checkIndex(index);
        return lines.get(index);
    }

    /** Sets a line of the new text; index from 0 (top) to {@code getLines().size() - 1}. */
    public void setLine(int index, String line) {
        checkIndex(index);
        lines.set(index, Objects.requireNonNull(line, "line; use \"\" for an empty line"));
        changed = true;
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= lines.size()) {
            throw new IndexOutOfBoundsException("A sign side has lines 0 to " + (lines.size() - 1) + ", got " + index);
        }
    }

    /** Whether the front of the sign is being written, rather than the back. */
    public boolean isFrontText() {
        return front;
    }

    /** Whether a listener changed any line. */
    public boolean isChanged() {
        return changed;
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
