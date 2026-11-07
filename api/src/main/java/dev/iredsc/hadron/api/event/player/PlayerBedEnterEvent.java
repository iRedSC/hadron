package dev.iredsc.hadron.api.event.player;

import dev.iredsc.hadron.api.event.Cancellable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * A player is about to lie down in a bed, having passed vanilla's checks (night, no monsters near). Cancel to keep
 * them up. The bed has already become their spawn point by then, as it does in vanilla when they try to sleep by day.
 */
public class PlayerBedEnterEvent extends PlayerEvent implements Cancellable {
    private final BlockPos bed;
    private boolean cancelled;

    public PlayerBedEnterEvent(ServerPlayer player, BlockPos bed) {
        super(player);
        this.bed = bed.immutable();
    }

    public BlockPos getBed() {
        return bed;
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
