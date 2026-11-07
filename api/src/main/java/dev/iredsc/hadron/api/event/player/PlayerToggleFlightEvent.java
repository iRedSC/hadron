package dev.iredsc.hadron.api.event.player;

import dev.iredsc.hadron.api.event.Cancellable;
import net.minecraft.server.level.ServerPlayer;

/**
 * A player starts or stops flying, where they're allowed to. Cancel to keep the server's state as it was; their own screen may still show the change for a
 * moment.
 */
public class PlayerToggleFlightEvent extends PlayerEvent implements Cancellable {
    private final boolean flying;
    private boolean cancelled;

    public PlayerToggleFlightEvent(ServerPlayer player, boolean flying) {
        super(player);
        this.flying = flying;
    }

    /** True when they're starting, false when stopping. */
    public boolean isFlying() {
        return flying;
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
