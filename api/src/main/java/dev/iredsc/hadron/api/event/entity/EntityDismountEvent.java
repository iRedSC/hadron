package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.event.Places;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Objects;
import net.minecraft.world.entity.Entity;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * An entity is about to stop riding another. {@link #getEntity()} is the rider. Cancel it and the rider stays on,
 * unless the dismount is {@link #isForced() forced}: then it happens anyway.
 */
@FiredBy(core = FULL)
public class EntityDismountEvent extends EntityEvent implements Cancellable {
    private final Entity dismounted;
    private final boolean forced;
    private boolean cancelled;

    public EntityDismountEvent(Entity rider, Entity dismounted, boolean forced, Actor actor) {
        super(rider, actor);
        this.dismounted = Objects.requireNonNull(dismounted, "dismounted");
        this.forced = forced;
    }

    /** What was being ridden. */
    public Entity getDismounted() {
        return dismounted;
    }

    /**
     * True when the dismount can't be refused: the rider or the vehicle is dying, being removed or teleporting.
     * Cancelling a forced dismount does nothing.
     */
    public boolean isForced() {
        return forced;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    /** Where the rider is, then where what it leaves is. */
    @Override
    public Places getPlaces() {
        return super.getPlaces().and(Place.of(getDismounted()));
    }
}
