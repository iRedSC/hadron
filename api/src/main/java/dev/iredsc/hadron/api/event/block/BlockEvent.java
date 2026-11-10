package dev.iredsc.hadron.api.event.block;

import java.util.ArrayList;
import dev.iredsc.hadron.api.event.Places;
import dev.iredsc.hadron.api.event.Place;
import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.ActionEvent;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Anything that happens to a block's space. Listen for this to hear all of them, from every actor: a protection
 * plugin listening here covers breaks, places, burns, flows, data changes and moves, including ones added later.
 *
 * <p>Every block event says where it happens ({@link #getLevel()}, {@link #getPos()}) and lists every space it
 * affects, each with its state and block entity data before and after ({@link #getBlocks()}). A protection plugin
 * can check each of those positions without knowing which kind of event it is.
 */
public abstract class BlockEvent extends ActionEvent {
    private final ServerLevel level;
    private final BlockPos pos;
    private final BlockState state;
    private final List<BlockChange> blocks;

    /**
     * @param pos where it happens: the block broken, placed, used, or the origin of a multi-block change
     * @param state the block it is about at {@code pos}
     * @param blocks every space affected; at least one
     */
    protected BlockEvent(ServerLevel level, BlockPos pos, BlockState state, List<BlockChange> blocks, Actor actor) {
        super(actor);
        this.level = Objects.requireNonNull(level, "level");
        this.pos = Objects.requireNonNull(pos, "pos").immutable();
        this.state = Objects.requireNonNull(state, "state");
        this.blocks = List.copyOf(blocks);
        if (this.blocks.isEmpty()) {
            throw new IllegalArgumentException("A block event needs at least one BlockChange: list each space it "
                    + "affects, or use BlockChange.unchanged(level, pos, state) for a space only involved");
        }
    }

    public ServerLevel getLevel() {
        return level;
    }

    /** Where it happens: the block broken, placed or used, or the origin of a change spanning several blocks. */
    public BlockPos getPos() {
        return pos;
    }

    /** The block this event is about: the one being broken, the one being placed, the one being used. */
    public BlockState getState() {
        return state;
    }

    /** Every block space this affects, with its state and block entity data before and after. Never empty. */
    public List<BlockChange> getBlocks() {
        return blocks;
    }

    /**
     * The player who did it, if a player is behind it and online: directly, or as the owner of the machine or fake
     * player that did. Empty when nature or an ownerless machine did it. Same as {@link #getActingPlayer()}.
     */
    public Optional<ServerPlayer> getPlayer() {
        return getActingPlayer();
    }

    /** The change at {@link #getPos()}, or the first one if that position isn't listed. */
    protected BlockChange primary() {
        for (BlockChange change : blocks) {
            if (change.getPos().equals(pos)) return change;
        }
        return blocks.get(0);
    }

    /** Where it happens: {@link #getPos()}, then each space in {@link #getBlocks()}, one block each. */
    @Override
    public Places getPlaces() {
        List<Place> spaces = new ArrayList<>();
        for (BlockChange change : getBlocks()) spaces.add(Place.Spot.at(getLevel(), change.getPos()));
        return Places.of(Place.Spot.at(getLevel(), getPos()), spaces);
    }
}
