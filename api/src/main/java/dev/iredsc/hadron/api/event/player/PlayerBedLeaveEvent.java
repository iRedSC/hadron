package dev.iredsc.hadron.api.event.player;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * A player is getting out of bed: morning came, they left, or the bed broke. Not cancellable, so nobody is stuck
 * in bed.
 */
public class PlayerBedLeaveEvent extends PlayerEvent {
    private final BlockPos bed;

    public PlayerBedLeaveEvent(ServerPlayer player, BlockPos bed) {
        super(player);
        this.bed = bed == null ? null : bed.immutable();
    }

    /** The bed they were in; empty if it's already gone. */
    public Optional<BlockPos> getBed() {
        return Optional.ofNullable(bed);
    }
}
