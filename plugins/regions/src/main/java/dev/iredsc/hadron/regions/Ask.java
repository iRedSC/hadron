package dev.iredsc.hadron.regions;

import dev.iredsc.hadron.api.event.Carried;
import dev.iredsc.hadron.api.event.Place;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** One question an event puts to the regions, as {@link Nodes} reads it from the event's own facts. */
sealed interface Ask {
    /**
     * The event's actor does {@code node} at every one of {@code places}.
     *
     * @param home where the world on its own sets out, when the event states a source (a flow's, a spread's, a
     *     fire's); empty to use the default: the block itself for a block event, otherwise the event's places
     * @param setOutFrom where the act set out from, when its event states it (a hit's {@code getSourceLocation()});
     *     decided on only for a node whose flag {@linkplain Bundle#bothEnds judges both ends}
     */
    record Act(Node node, List<Place> places, Optional<List<Place>> home, Optional<Place> setOutFrom) implements Ask {
        public Act {
            Objects.requireNonNull(node, "node");
            places = List.copyOf(places);
            Objects.requireNonNull(home, "home");
            home = home.map(List::copyOf);
            Objects.requireNonNull(setOutFrom, "setOutFrom");
        }

        Act(Node node, List<Place> places, Optional<List<Place>> home) {
            this(node, places, home, Optional.empty());
        }

        Act(Node node, List<Place> places) {
            this(node, places, Optional.empty());
        }

        /** Every place a region decides it at: its places, and where it set out from when its node judges both ends. */
        List<Place> decidedAt() {
            if (setOutFrom.isEmpty() || !node.bundle().bothEnds()) return places;
            List<Place> out = new ArrayList<>(places);
            out.add(setOutFrom.get());
            return List.copyOf(out);
        }
    }

    /**
     * The event's actor does {@code node} to each of {@code blocks}, and a block a region refuses is spared from the
     * event rather than the whole event refused: an explosion's list. {@code blocks} is the event's own, live list.
     */
    record Spare(Node node, ServerLevel level, List<BlockPos> blocks, List<Place> home) implements Ask {
        public Spare {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(blocks, "blocks");
            home = List.copyOf(home);
        }
    }

    /**
     * Each player this moves enters where they end up, asking {@code action} as themselves, not as the actor: those
     * {@code moved} and everyone {@code carried}, each judged from where that one sets out to where that one ends up,
     * and from where {@code carrier}, the entity carrying them all, sets out to where it ends up (a seat can be lower or
     * further over than its vehicle). No carrier when no entity carries them: a ship's crew.
     */
    record Enter(Action action, Optional<Carried.Ride> carrier, List<Carried.Ride> moved, Carried carried)
            implements Ask {
        public Enter {
            if (action.kinded() || action.key().indexOf("enter.") != 0) {
                throw new IllegalArgumentException(action.key() + " isn't an enter.* node");
            }
            Objects.requireNonNull(carrier, "carrier");
            moved = List.copyOf(moved);
            Objects.requireNonNull(carried, "carried");
        }

        Node node() {
            return Node.of(action);
        }
    }
}
