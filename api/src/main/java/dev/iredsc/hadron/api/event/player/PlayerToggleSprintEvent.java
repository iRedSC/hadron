package dev.iredsc.hadron.api.event.player;

import dev.iredsc.hadron.api.event.Cancellable;
import net.minecraft.server.level.ServerPlayer;

/**
 * A player starts or stops sprinting. Cancel to keep the server's state as it was; their own screen may still show the change for a
 * moment.
 */
public class PlayerToggleSprintEvent extends PlayerEvent implements Cancellable {
    private final boolean sprinting;
    private boolean cancelled;

    public PlayerToggleSprintEvent(ServerPlayer player, boolean sprinting) {
        super(player);
        this.sprinting = sprinting;
    }

    /** True when they're starting, false when stopping. */
    public boolean isSprinting() {
        return sprinting;
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
