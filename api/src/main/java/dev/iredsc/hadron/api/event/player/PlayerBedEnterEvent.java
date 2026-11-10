package dev.iredsc.hadron.api.event.player;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.server.level.ServerLevel;
import java.util.List;
import dev.iredsc.hadron.api.event.Places;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A player is about to lie down in a bed, having passed vanilla's checks (night, no monsters near). Cancel to keep
 * them up. The bed has already become their spawn point by then, as it does in vanilla when they try to sleep by day.
 */
@FiredBy(core = FULL)
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

    /** Where the player is, then both halves of the bed. */
    @Override
    public Places getPlaces() {
        return super.getPlaces().and(halves(getPlayer().serverLevel(), bed));
    }

    /** A bed's spaces: {@code pos}, and its other half when it is a vanilla-shaped bed. */
    static List<Place> halves(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof BedBlock)) return List.of(Place.Spot.at(level, pos));
        return List.of(Place.Spot.at(level, pos),
                Place.Spot.at(level, pos.relative(BedBlock.getConnectedDirection(state))));
    }
}
