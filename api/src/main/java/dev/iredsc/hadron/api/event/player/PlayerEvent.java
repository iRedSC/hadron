package dev.iredsc.hadron.api.event.player;

import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.event.Placed;
import dev.iredsc.hadron.api.event.Places;
import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;

/**
 * Anything about one player, who is always there: {@link #getPlayer()} is never null. Listen for this to hear all of
 * them. Events any actor can cause (breaking a block, dropping an item) are not player events, even when a player
 * did them; see {@code ActionEvent#getActingPlayer()}.
 */
public abstract class PlayerEvent extends Event implements Placed {
    private final ServerPlayer player;

    protected PlayerEvent(ServerPlayer player) {
        this.player = Objects.requireNonNull(player, "player");
    }

    /** The player this is about. */
    public ServerPlayer getPlayer() {
        return player;
    }

    /**
     * Where it happens: the block the player stands in now. Events that touch more (a bed, a respawn's arrival) add
     * each; a login, before the player is placed, is {@link Place.Nowhere}.
     */
    @Override
    public Places getPlaces() {
        return Places.of(Place.of(player));
    }
}
