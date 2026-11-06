package dev.iredsc.hadron.api.event.player;

import dev.iredsc.hadron.api.event.Cancellable;
import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * The server is about to disconnect a player who is in the game: {@code /kick}, a timeout, a mod, a rejected packet.
 * Change what they see, or cancel to keep them connected. Doesn't fire while the server shuts down, or when a newer
 * login of the same account replaces them, since neither can be refused.
 */
public class PlayerKickEvent extends PlayerEvent implements Cancellable {
    private Component reason;
    private boolean cancelled;

    public PlayerKickEvent(ServerPlayer player, Component reason) {
        super(player);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /** What the player sees on their disconnect screen. */
    public Component getReason() {
        return reason;
    }

    public void setReason(Component reason) {
        this.reason = Objects.requireNonNull(reason, "Kick reason cannot be null; use Component.empty() for none");
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
