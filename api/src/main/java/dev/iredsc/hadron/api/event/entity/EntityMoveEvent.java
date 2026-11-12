package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.event.Carried;
import dev.iredsc.hadron.api.event.Places;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A player, or a vehicle someone is driving, has moved into another block, or carried something riding it into
 * another block: a turning boat swings its back seat across a block edge while the boat stays in its own. Fired only
 * when a block changes, so it is cheap to listen to: once per tick before the entity moves on, and again when a
 * rider's own tick seats it in another block (a rearing horse tips its rider back during the horse's tick). Cancel it
 * and the entity is put back where it was, a vehicle also facing and posed as it was (a camel sitting down is held
 * where its sitting had got to); a rider whose seat still moves on its own is held where it was until the next check,
 * so no rider ends up in a refused block.
 *
 * <p>A player riding something is covered by the vehicle's event, not their own: the vehicle's {@link #getCarried()}
 * names them, and its places include where each rider is, which, when the event fires, is the seat the move leaves
 * them in.
 */
@FiredBy(core = FULL)
public class EntityMoveEvent extends EntityEvent implements Cancellable {
    private final Vec3 from;
    private final Vec3 to;
    private boolean cancelled;

    public EntityMoveEvent(Entity entity, Vec3 from, Vec3 to, Actor actor) {
        super(entity, actor);
        this.from = Objects.requireNonNull(from, "from");
        this.to = Objects.requireNonNull(to, "to");
    }

    /** Where it was at the last check; in the block it left, unless only a rider changed block. */
    public Vec3 getFrom() {
        return from;
    }

    /** Where it is now. */
    public Vec3 getTo() {
        return to;
    }

    public BlockPos getFromBlock() {
        return BlockPos.containing(from);
    }

    public BlockPos getToBlock() {
        return BlockPos.containing(to);
    }

    /**
     * Everything riding it, riders of riders included, which moves with it, each already in the seat the move leaves it
     * in, which is where each ride ends up: read from the entity on each call, so a rider a listener takes off is no
     * longer named. Always {@link Carried.Known}.
     */
    public Carried getCarried() {
        List<Carried.Ride> rides = new ArrayList<>();
        for (Entity rider : getEntity().getIndirectPassengers()) rides.add(new Carried.Ride(rider, Place.of(rider)));
        return Carried.of(rides);
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    /** The block it leaves, the block it enters, then where the move leaves each entity it carries. */
    @Override
    public Places getPlaces() {
        return Places.of(Place.Spot.at(getLevel(), getFromBlock()), Place.Spot.at(getLevel(), getToBlock()))
                .and(getCarried().places());
    }
}
