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
 * seated, which can be another block than the mount's (a boat's back seat), and {@link #getCarried()} is everyone
 * else it moves: who the rider brings along, and who is already aboard and has to shift. Cancel it and nobody mounts
 * or moves.
 */
@FiredBy(core = FULL)
public class EntityMountEvent extends EntityEvent implements Cancellable {
    private final Entity mount;
    private final Vec3 to;
    private final Carried carried;
    private boolean cancelled;

    /**
     * {@code to} is where the rider is once seated on {@code mount}, and {@code carried} is every other entity the
     * mount moves, with where it ends up: each entity riding the rider, riders of riders included, and each rider
     * already on {@code mount} whose seat changes, with its own riders; {@link Carried#none()} when it moves nobody
     * else. State the seat the mount will really give, never the mount's own position standing in for it: a seat can
     * lie in another block.
     */
    public EntityMountEvent(Entity rider, Entity mount, Vec3 to, Carried carried, Actor actor) {
        super(rider, actor);
        this.mount = Objects.requireNonNull(mount, "mount");
        this.to = Objects.requireNonNull(to, "to; where the rider is seated once on the mount");
        this.carried = Objects.requireNonNull(carried, "carried; Carried.none() when the mount moves nobody else");
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
     * Every other entity this mount moves. First everything riding the rider, riders of riders included, which mounts
     * along with it. Then each rider already on the mount that has to shift to make room (a boat's lone rider moves
     * forward for a second; a mob driving a boat goes to the back seat when a player boards), with its own riders; a
     * rider that stays in its seat is not listed. Each ride sets out from where the entity is and ends where it is
     * seated once the rider is, and it is put exactly there as the rider mounts. A ride ends in one block where its
     * seat can't turn with its carrier, and otherwise in the area the seat can swing through as the mount turns the
     * rider. {@link Carried#none()} when the mount moves nobody else.
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
     * carries, and each rider already aboard that it shifts, sets out and ends up.
     */
    @Override
    public Places getPlaces() {
        return super.getPlaces().and(Place.of(getMount()), Place.Spot.at(getLevel(), getTo()))
                .and(getCarried().places());
    }
}
