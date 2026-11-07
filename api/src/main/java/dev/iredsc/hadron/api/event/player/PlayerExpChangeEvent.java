package dev.iredsc.hadron.api.event.player;

import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import net.minecraft.server.level.ServerPlayer;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A player is about to gain or lose vanilla experience points: an orb, {@code /xp add}, a mod paying out. Change the
 * amount, or cancel so nothing changes. Setting experience outright ({@code /xp set}) and enchanting don't fire this;
 * their level change still fires {@link PlayerLevelChangeEvent}. Mods' own skill points are resources, not this.
 */
@FiredBy(core = FULL)
public class PlayerExpChangeEvent extends PlayerEvent implements Cancellable {
    private int amount;
    private boolean cancelled;

    public PlayerExpChangeEvent(ServerPlayer player, int amount) {
        super(player);
        this.amount = amount;
    }

    /** Points gained; negative for a loss. */
    public int getAmount() {
        return amount;
    }

    public void setAmount(int amount) {
        this.amount = amount;
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
