package dev.iredsc.hadron.api.event.player;

import dev.iredsc.hadron.api.event.FiredBy;
import org.jspecify.annotations.Nullable;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A player is leaving; they're still in the world while this runs, so it's the place to save their data.
 * {@link #getQuitMessage()} is the "left the game" line about to be broadcast; change it with
 * {@link #setQuitMessage}, or set null for none. It replaces vanilla's line.
 */
@FiredBy(core = FULL)
public class PlayerQuitEvent extends PlayerEvent {
    private @Nullable Component quitMessage;

    public PlayerQuitEvent(ServerPlayer player) {
        this(player, null);
    }

    /** @param quitMessage the line about to be broadcast, or null for none */
    public PlayerQuitEvent(ServerPlayer player, @Nullable Component quitMessage) {
        super(player);
        this.quitMessage = quitMessage;
    }

    /** The line about to be broadcast to everyone, or null when none will be. */
    public @Nullable Component getQuitMessage() {
        return quitMessage;
    }

    /** Broadcast this instead; null or an empty component broadcasts nothing. */
    public void setQuitMessage(@Nullable Component quitMessage) {
        this.quitMessage = quitMessage;
    }
}
