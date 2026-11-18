package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.event.Carried;
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
 * An entity is about to start riding another: a player boarding a boat or horse, a mob entering a minecart, a mod's
 * vehicle or a player carrying another. {@link #getEntity()} is the rider, {@link #getTo()} is where it will be
 * seated, which can be another block than the mount's (a boat's back seat), and {@link #getCarried()} is everyone the
 * rider brings along. Cancel it and nobody mounts.
 */
@FiredBy(core = FULL)
public class EntityMountEvent extends EntityEvent implements Cancellable {
    private final Entity mount;
    private final Vec3 to;
    private final Carried carried;
    private boolean cancelled;

    /**
     * {@code to} is where the rider is once seated on {@code mount}, and {@code carried} is each entity riding the
     * rider, riders of riders included, with where it ends up; {@link Carried#none()} when it has none. State the seat
     * the mount will really give, never the mount's own position standing in for it: a seat can lie in another block.
     */
    public EntityMountEvent(Entity rider, Entity mount, Vec3 to, Carried carried, Actor actor) {
        super(rider, actor);
        this.mount = Objects.requireNonNull(mount, "mount");
        this.to = Objects.requireNonNull(to, "to; where the rider is seated once on the mount");
        this.carried = Objects.requireNonNull(carried, "carried; Carried.none() when nothing rides the rider");
    }

    /** What is being ridden. */
    public Entity getMount() {
        return mount;
    }

    /**
     * Where the rider is seated once it mounts, in its own world: worked out before this fired, with Minecraft's own
     * rule (the seat the mount gives this rider, among the riders it will then have, less where the rider attaches
     * standing), and the rider is put exactly there as it mounts. The mount then carries it on from here as it moves
     * ({@link EntityMoveEvent} says where). Cancelled, nothing moves.
     */
    public Vec3 getTo() {
        return to;
    }

    /**
     * Everything riding the rider, riders of riders included, which mounts along with it: each ride sets out from
     * where it is and ends where it is seated once the rider is. A ride ends in one block where its seat can't turn
     * with the rider, and otherwise in the area the seat can swing through as the mount turns the rider.
     * {@link Carried#none()} when nothing rides the rider.
     */
    public Carried getCarried() {
        return carried;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    /**
     * Where the rider is, then where what it mounts is, then where the rider is seated, then where each entity it
     * carries sets out and ends up.
     */
    @Override
    public Places getPlaces() {
        return super.getPlaces().and(Place.of(getMount()), Place.Spot.at(getLevel(), getTo()))
                .and(getCarried().places());
    }
}
