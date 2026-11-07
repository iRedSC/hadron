package dev.iredsc.hadron.api.event.player;

import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import net.minecraft.server.level.ServerPlayer;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A player starts or stops sneaking (crouching). Cancel to keep the server's state as it was; their own screen may still show the change for a
 * moment.
 */
@FiredBy(core = FULL)
public class PlayerToggleSneakEvent extends PlayerEvent implements Cancellable {
    private final boolean sneaking;
    private boolean cancelled;

    public PlayerToggleSneakEvent(ServerPlayer player, boolean sneaking) {
        super(player);
        this.sneaking = sneaking;
    }

    /** True when they're starting, false when stopping. */
    public boolean isSneaking() {
        return sneaking;
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
