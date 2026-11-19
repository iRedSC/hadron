package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.event.Places;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.decoration.HangingEntity;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * An item frame or painting is being hung, however it came to be. Cancel it and nothing is hung, and a player's item
 * is not spent. Its {@link #getCause()} is {@link EntitySpawnEvent.Cause#PLACED} when an item's use or a dispenser
 * hung it and {@link EntitySpawnEvent.Cause#SPAWNED} otherwise (a summon, a mod's own code), never
 * {@link EntitySpawnEvent.Cause#LAUNCHED}.
 */
@FiredBy(core = FULL)
public class HangingPlaceEvent extends EntitySpawnEvent {
    /**
     * @param cause {@link EntitySpawnEvent.Cause#PLACED} when an item's use or a placer hung it,
     *     {@link EntitySpawnEvent.Cause#SPAWNED} otherwise
     */
    public HangingPlaceEvent(HangingEntity hanging, Cause cause, Actor actor) {
        super(hanging, cause, null, actor);
        if (cause == Cause.LAUNCHED) {
            throw new IllegalArgumentException("a hanging entity is never launched; use Cause.PLACED when an item's "
                    + "use or a placer hung it, Cause.SPAWNED otherwise");
        }
    }

    @Override
    public HangingEntity getEntity() {
        return (HangingEntity) super.getEntity();
    }

    /** The block it hangs on. */
    public BlockPos getBlock() {
        return getEntity().getPos().relative(getEntity().getDirection().getOpposite());
    }

    /** The face of {@link #getBlock()} it hangs on. */
    public Direction getBlockFace() {
        return getEntity().getDirection();
    }

    /** Where the hanging entity is, then the block it hangs on. */
    @Override
    public Places getPlaces() {
        return super.getPlaces().and(Place.Spot.at(getLevel(), getBlock()));
    }
}
