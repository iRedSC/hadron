package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.ActionEvent;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Anything that happens to a block's space. Listen for this to hear all of them, from every actor: a protection
 * plugin listening here covers breaks, places, burns, flows, data changes and moves, including ones added later.
 */
public abstract class BlockEvent extends ActionEvent {
    private final ServerLevel level;
    private final BlockPos pos;
    private final BlockState state;

    protected BlockEvent(ServerLevel level, BlockPos pos, BlockState state, Actor actor) {
        super(actor);
        this.level = level;
        this.pos = pos.immutable();
        this.state = state;
    }

    public ServerLevel getLevel() {
        return level;
    }

    public BlockPos getPos() {
        return pos;
    }

    /** The block this event is about: the one being broken, or the one being placed. */
    public BlockState getState() {
        return state;
    }

    /**
     * The player who did it, if a player is behind it and online: directly, or as the owner of the machine or fake
     * player that did. Empty when nature or an ownerless machine did it. Same as {@link #getActingPlayer()}.
     */
    public Optional<ServerPlayer> getPlayer() {
        return getActingPlayer();
    }
}
