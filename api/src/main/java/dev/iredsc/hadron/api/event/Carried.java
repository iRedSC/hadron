package dev.iredsc.hadron.api.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The entities that move along with a move, and where each ends up: a vehicle's riders, a teleported entity's
 * passengers, a ship's crew. A move states them, so a plugin never works out riders itself. Sealed, so a
 * {@code switch} must handle both:
 *
 * <pre>{@code
 * switch (event.getCarried()) {
 *     case Carried.Known k -> k.rides()      // each entity and where it ends up; empty means none go along
 *     case Carried.Unknown u -> ...          // nobody can say before it moves: u.bound() holds wherever they could be
 * }
 * }</pre>
 *
 * <p>Never an empty list standing for "nobody knows": what a move can't know it carries is {@link Unknown}, so
 * protection can fail closed on it. The event's places include where each carried entity is and where it ends up, or,
 * for {@link Unknown}, a {@link Place.Unknown} with its bound.
 */
public sealed interface Carried {
    /**
     * One entity going along, and where it ends up: a {@link Place.Spot} (a block, or an area it lands somewhere in)
     * or a {@link Place.Unknown} with a bound. Built with {@link Carried#ride}, which takes the position it ends up at.
     */
    record Ride(Entity entity, Place to) {
        public Ride {
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(to, "to; where it ends up, such as Place.Spot.at(level, position)");
            if (!(to instanceof Place.Spot || to instanceof Place.Unknown)) {
                throw new IllegalArgumentException("A ride ends up at a Place.Spot, or a Place.Unknown when nothing "
                        + "can say where within its bound, not " + to);
            }
        }
    }

    /**
     * Exactly these entities move along with it, each once, riders of riders included, each with where it ends up.
     * Empty when nothing does. Unmodifiable.
     */
    record Known(List<Ride> rides) implements Carried {
        public Known {
            Set<Entity> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            List<Ride> once = new ArrayList<>();
            for (Ride r : Objects.requireNonNull(rides, "rides; use Carried.none() when nothing goes along")) {
                if (seen.add(Objects.requireNonNull(r, "a ride in rides").entity())) once.add(r);
            }
            rides = List.copyOf(once);
        }

        /** The entities going along, in order. */
        public List<Entity> entities() {
            return rides.stream().map(Ride::entity).toList();
        }
    }

    /**
     * Which entities move along with it can't be known when the event fires: {@link #reason()} says why, and
     * {@link #bound()} holds every place any of them could be, before and after. Protection should deny it wherever its
     * bound reaches somewhere it would deny an outsider.
     */
    record Unknown(Place.Bound bound, Reason reason) implements Carried {
        /** Why what a move carries is unknown. Closed: a new reason is a change to this list. */
        public enum Reason {
            /**
             * What it carries is decided on each tick of travel, after the event: a piston picks up what it bumps into
             * and what sticks to its slime or honey as it goes.
             */
            DECIDED_AS_IT_MOVES,
            /** A mod moves entities in a way its bridge can't see before it happens. */
            UNTRACED
        }

        public Unknown {
            Objects.requireNonNull(bound, "bound; Place.Anywhere.INSTANCE when nothing narrows it");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** {@code entity} goes along and ends up at {@code position} in {@code level}. */
    static Ride ride(Entity entity, ServerLevel level, Vec3 position) {
        return new Ride(entity, Place.Spot.at(level, Objects.requireNonNull(position, "position")));
    }

    /** Exactly these rides. */
    static Known of(List<Ride> rides) {
        return new Known(rides);
    }

    /** Exactly these rides. */
    static Known of(Ride... rides) {
        return new Known(List.of(rides));
    }

    /** Nothing goes along, known for certain. */
    static Known none() {
        return new Known(List.of());
    }

    /** Nobody can say what goes along; whatever does is within {@code bound}. */
    static Unknown unknown(Place.Bound bound, Unknown.Reason reason) {
        return new Unknown(bound, reason);
    }

    /**
     * Where each carried entity is now and where it ends up; for {@link Unknown}, one {@link Place.Unknown} over its
     * bound.
     */
    default List<Place> places() {
        return switch (this) {
            case Known k -> {
                List<Place> out = new ArrayList<>();
                for (Ride r : k.rides()) {
                    out.add(Place.of(r.entity()));
                    out.add(r.to());
                }
                yield out;
            }
            case Unknown u -> List.of(new Place.Unknown(u.bound(), Place.Unknown.Reason.UNKNOWN_CARRIAGE));
        };
    }
}
