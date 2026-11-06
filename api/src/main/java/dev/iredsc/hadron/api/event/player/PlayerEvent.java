package dev.iredsc.hadron.api.event.player;

import dev.iredsc.hadron.api.event.Event;
import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;

/**
 * Anything about one player, who is always there: {@link #getPlayer()} is never null. Listen for this to hear all of
 * them. Events any actor can cause (breaking a block, dropping an item) are not player events, even when a player
 * did them; see {@code ActionEvent#getActingPlayer()}.
 */
public abstract class PlayerEvent extends Event {
    private final ServerPlayer player;

    protected PlayerEvent(ServerPlayer player) {
        this.player = Objects.requireNonNull(player, "player");
    }

    /** The player this is about. */
    public ServerPlayer getPlayer() {
        return player;
    }
}
