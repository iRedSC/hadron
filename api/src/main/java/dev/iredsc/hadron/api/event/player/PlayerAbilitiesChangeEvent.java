package dev.iredsc.hadron.api.event.player;

import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import net.minecraft.server.level.ServerPlayer;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A player is about to be allowed to fly, or their fly or walk speed is about to change. Cancel to keep everything as
 * it was. Losing flight is {@link PlayerFlightRevokeEvent} instead.
 */
@FiredBy(core = FULL)
public class PlayerAbilitiesChangeEvent extends PlayerAbilitiesEvent implements Cancellable {
    private boolean cancelled;

    public PlayerAbilitiesChangeEvent(ServerPlayer player, boolean oldAllowFlight, float oldFlySpeed,
            float oldWalkSpeed, boolean allowFlight, float flySpeed, float walkSpeed) {
        super(player, oldAllowFlight, oldFlySpeed, oldWalkSpeed, allowFlight, flySpeed, walkSpeed);
        if (oldAllowFlight && !allowFlight) {
            throw new IllegalArgumentException("Losing flight can't be cancelled; fire PlayerFlightRevokeEvent instead");
        }
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
