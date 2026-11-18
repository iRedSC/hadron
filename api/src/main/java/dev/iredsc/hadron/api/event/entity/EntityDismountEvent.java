package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.event.Places;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Objects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * An entity is about to stop riding another. {@link #getEntity()} is the rider, and {@link #getTo()} is where it is
 * set down. Cancel it and the rider stays on, unless the dismount is {@link #isForced() forced}: then it happens
 * anyway.
 */
@FiredBy(core = FULL)
public class EntityDismountEvent extends EntityEvent implements Cancellable {
    private final Entity dismounted;
    private final Vec3 to;
    private final boolean forced;
    private boolean cancelled;

    /**
     * {@code to} is where the rider ends up once off: where it is placed ({@link Landing#of} that spot), or its own
     * position when nothing places it.
     */
    public EntityDismountEvent(Entity rider, Entity dismounted, Vec3 to, boolean forced, Actor actor) {
        super(rider, actor);
        this.dismounted = Objects.requireNonNull(dismounted, "dismounted");
        this.to = Objects.requireNonNull(to, "to; the rider's own position when nothing moves it");
        this.forced = forced;
    }

    /** What was being ridden. */
    public Entity getDismounted() {
        return dismounted;
    }

    /**
     * Where the rider is set down, in its own world: worked out before this fired, with Minecraft's own rule (beside the
     * vehicle where there is room for it, crouching if it must), and then applied exactly. A rider that isn't a living
     * entity, or that is being removed, stays where it is. When it is getting off because it teleports, the teleport
     * then takes it on from here ({@link EntityTeleportEvent} says where). Cancelled, nothing moves.
     */
    public Vec3 getTo() {
        return to;
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

    /** Where the rider is, then where what it leaves is, then where it is set down. */
    @Override
    public Places getPlaces() {
        return super.getPlaces().and(Place.of(getDismounted())).and(Place.Spot.at(getLevel(), getTo()));
    }
}
