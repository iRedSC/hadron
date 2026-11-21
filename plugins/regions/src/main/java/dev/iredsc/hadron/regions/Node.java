package dev.iredsc.hadron.regions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One leaf of the node tree, what an event asks a region about: an {@link Action}, and the {@link Kind} of entity when
 * the action takes one. Can't be built with a kind its action doesn't take, or without one it does.
 */
record Node(Action action, Optional<Kind> kind) {
    Node {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(kind, "kind");
        if (action.kinded() != kind.isPresent()) {
            throw new IllegalArgumentException(action.key() + (action.kinded()
                    ? " acts on an entity: use Node.of(action, kind)" : " takes no kind: use Node.of(action)"));
        }
    }

    static Node of(Action action) {
        return new Node(action, Optional.empty());
    }

    static Node of(Action action, Kind kind) {
        return new Node(action, Optional.of(kind));
    }

    /** As written: {@code entity.hurt.animal}, {@code block.break}. */
    String key() {
        return kind.map(k -> action.key() + "." + k.key()).orElse(action.key());
    }

    List<String> segments() {
        return List.of(key().split("\\."));
    }

    /** The flag holding it; {@link Bundle#OPEN} for none. */
    Bundle bundle() {
        return action.bundle(kind.orElse(Kind.MISC));
    }

    /** Every node there is, in the order of {@link Action}, then {@link Kind}. */
    static List<Node> all() {
        List<Node> out = new ArrayList<>();
        for (Action a : Action.values()) {
            if (!a.kinded()) {
                out.add(of(a));
                continue;
            }
            for (Kind k : Kind.values()) out.add(of(a, k));
        }
        return out;
    }

    @Override
    public String toString() {
        return key();
    }
}
