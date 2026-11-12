package dev.iredsc.hadron.api.event;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.world.entity.Entity;

/**
 * The entities that move along with a move: a vehicle's riders, a teleported entity's passengers, a ship's crew. A
 * move states them, so a plugin never works out riders itself. Sealed, so a {@code switch} must handle both:
 *
 * <pre>{@code
 * switch (event.getCarried()) {
 *     case Carried.Known k -> k.entities()   // exactly these go along; empty means none do
 *     case Carried.Unknown u -> ...          // nobody can say before it moves: deny, as for an outsider
 * }
 * }</pre>
 *
 * <p>Never an empty list standing for "nobody knows": what a move can't know it carries is {@link Unknown}, so
 * protection can fail closed on it.
 */
public sealed interface Carried {
    /**
     * Exactly these entities move along with it, each once, riders of riders included. Empty when nothing does.
     * Unmodifiable.
     */
    record Known(List<Entity> entities) implements Carried {
        public Known {
            Set<Entity> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            List<Entity> once = new ArrayList<>();
            for (Entity e : Objects.requireNonNull(entities, "entities; use Carried.none() when nothing goes along")) {
                if (seen.add(Objects.requireNonNull(e, "an entity in entities"))) once.add(e);
            }
            entities = List.copyOf(once);
        }
    }

    /**
     * Which entities move along with it can't be known when the event fires: {@link #reason()} says why. Protection
     * should deny it wherever it would deny an outsider.
     */
    record Unknown(Reason reason) implements Carried {
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
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Exactly these entities go along. */
    static Known of(Collection<? extends Entity> entities) {
        return new Known(List.copyOf(Objects.requireNonNull(entities, "entities")));
    }

    /** Everything riding {@code vehicle} now, riders of riders included, in the order they ride. */
    static Known ridersOf(Entity vehicle) {
        List<Entity> riders = new ArrayList<>();
        Objects.requireNonNull(vehicle, "vehicle").getIndirectPassengers().forEach(riders::add);
        return new Known(riders);
    }

    /** Nothing goes along, known for certain. */
    static Known none() {
        return new Known(List.of());
    }

    /** Nobody can say what goes along, and why. */
    static Unknown unknown(Unknown.Reason reason) {
        return new Unknown(reason);
    }

    /** Where each carried entity stands now ({@link Place#of(Entity)}); none for {@link Unknown}. */
    default List<Place> places() {
        return switch (this) {
            case Known k -> k.entities().stream().map(Place::of).toList();
            case Unknown u -> List.of();
        };
    }
}
