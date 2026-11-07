package dev.iredsc.hadron.api.event.player;

import dev.iredsc.hadron.api.event.FiredBy;
import org.jspecify.annotations.Nullable;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A player is getting out of bed: morning came, they left, or the bed broke. Not cancellable, so nobody is stuck
 * in bed.
 */
@FiredBy(core = FULL)
public class PlayerBedLeaveEvent extends PlayerEvent {
    private final @Nullable BlockPos bed;

    public PlayerBedLeaveEvent(ServerPlayer player, @Nullable BlockPos bed) {
        super(player);
        this.bed = bed == null ? null : bed.immutable();
    }

    /** The bed they were in; empty if it's already gone. */
    public Optional<BlockPos> getBed() {
        return Optional.ofNullable(bed);
    }
}
