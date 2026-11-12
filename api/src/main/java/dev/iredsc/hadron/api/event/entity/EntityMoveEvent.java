package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.event.Carried;
import dev.iredsc.hadron.api.event.Places;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * An entity has moved into another block, or carried something riding it into another block: a player walking, a
 * vehicle someone drives (a turning boat swings its back seat across a block edge while the boat stays in its own), or
 * any entity a piston pushes. Fired only when a block changes, so it is cheap to listen to, and never on a guess:
 * <ul>
 *   <li>a move a client sends (walking, steering its vehicle) is asked about where the server applies the packet,
 *       with every rider seated, before any other client is sent it; refused, the mover's client is corrected (what
 *       the move pressed or counted on the way stays: a known gap);</li>
 *   <li>a move a vehicle makes in its own tick (a mount rearing, a camel sitting down) is asked about at the end of
 *       that tick, once vanilla has seated every passenger, before any client is sent it;</li>
 *   <li>a piston's push is asked about before it is made, with where vanilla's own limit and collision (a wall, a
 *       step up) will take the entity and its riders; refused, nothing moves and nothing is stepped on;</li>
 *   <li>a move something else makes between ticks (a mod or plugin setting a vehicle's or player's position) is asked
 *       about at the end of its next tick: the server may already have sent that move to watching clients, who then
 *       see it put back if refused.</li>
 * </ul>
 *
 * <p>The actor is who moved it: the player for their own move, the player driving a vehicle, the piston for a push.
 * Cancel it and all of it is put back where it was: the entity, facing and posed as it was (a camel sitting down is
 * held where its sitting had got to), and every rider.
 *
 * <p>A player riding something is covered by the vehicle's event, not their own: the vehicle's {@link #getCarried()}
 * names them, each with the block it set out from and the one it ended up in, both among its places.
 */
@FiredBy(core = FULL)
public class EntityMoveEvent extends EntityEvent implements Cancellable {
    private final Vec3 from;
    private final Vec3 to;
    /** Where each rider was before the move, by identity; a rider missing from it sets out from where it is. */
    private final Map<Entity, Vec3> ridersFrom;
    /** Where each rider ends up, for a move asked about before it is made; a rider missing from it is where it is. */
    private final Map<Entity, Vec3> ridersTo;
    private boolean cancelled;

    /** A move whose riders set out from where they are when the event is read. */
    public EntityMoveEvent(Entity entity, Vec3 from, Vec3 to, Actor actor) {
        this(entity, from, to, Map.of(), actor);
    }

    /**
     * A move whose riders set out from {@code ridersFrom}: where each was before the move seated it again, so a rider
     * swung out of a block is placed in the block it left as well as the one it ends up in.
     */
    public EntityMoveEvent(Entity entity, Vec3 from, Vec3 to, Map<Entity, Vec3> ridersFrom, Actor actor) {
        this(entity, from, to, ridersFrom, Map.of(), actor);
    }

    /**
     * A move asked about before it is made (a piston's push): each rider sets out from {@code ridersFrom} and ends up
     * at {@code ridersTo}, where the move is about to put it.
     */
    public EntityMoveEvent(Entity entity, Vec3 from, Vec3 to, Map<Entity, Vec3> ridersFrom, Map<Entity, Vec3> ridersTo,
            Actor actor) {
        super(entity, actor);
        this.from = Objects.requireNonNull(from, "from");
        this.to = Objects.requireNonNull(to, "to");
        this.ridersFrom = copy(ridersFrom, "ridersFrom; Map.of() when riders set out from where they are");
        this.ridersTo = copy(ridersTo, "ridersTo; Map.of() when riders already are where they end up");
    }

    private static Map<Entity, Vec3> copy(Map<Entity, Vec3> riders, String what) {
        Map<Entity, Vec3> copy = new IdentityHashMap<>();
        Objects.requireNonNull(riders, what).forEach((rider, at) -> copy.put(Objects.requireNonNull(rider, "a rider"),
                Objects.requireNonNull(at, "where a rider is")));
        return copy;
    }

    /** Where it was before the move; in the block it left, unless only a rider changed block. */
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
     * Everything riding it, riders of riders included, which moved with it: each ride sets out from where the rider
     * was before the move and ends where the move seated it, both observed. Read from the entity on each call, so a
     * rider a listener takes off is no longer named. Always {@link Carried.Known}.
     */
    public Carried getCarried() {
        List<Carried.Ride> rides = new ArrayList<>();
        for (Entity rider : getEntity().getIndirectPassengers()) {
            Vec3 was = ridersFrom.get(rider);
            Vec3 going = ridersTo.get(rider);
            Place here = Place.of(rider);
            Place to = going != null && here instanceof Place.Spot s ? Place.Spot.at(s.level(), going) : here;
            Place from = was != null && here instanceof Place.Spot s ? Place.Spot.at(s.level(), was) : here;
            rides.add(new Carried.Ride(rider, from, to));
        }
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

    /**
     * The block it leaves, the block it enters, and, when those aren't the same block or next to each other through a
     * face (a step up and along, a jump of several blocks), every block between them as one area, so no block it
     * crossed is left out; then where each entity it carries sets out and ends up.
     */
    @Override
    public Places getPlaces() {
        BlockPos a = getFromBlock();
        BlockPos b = getToBlock();
        List<Place> more = new ArrayList<>();
        more.add(Place.Spot.at(getLevel(), b));
        if (a.distManhattan(b) > 1) {
            more.add(new Place.Spot(getLevel(), net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(a,
                    b)));
        }
        more.addAll(getCarried().places());
        return Places.of(Place.Spot.at(getLevel(), a), more);
    }
}
