package dev.iredsc.hadron.api.event;

import java.util.AbstractList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.Set;

/**
 * Every place an action happens, in the order its parts name them, each once. Never empty: it can only be built
 * from at least one {@link Place}, so an event can't say it happens nowhere by mistake (it says {@link Place.Nowhere}
 * or {@link Place.Unknown} instead). Unmodifiable.
 */
public final class Places extends AbstractList<Place> implements RandomAccess {
    private final List<Place> places;

    private Places(List<Place> places) {
        this.places = places;
    }

    /** The first place and any more, each once. */
    public static Places of(Place first, Place... more) {
        return of(first, List.of(more));
    }

    /** The first place and every one in {@code more}, each once. */
    public static Places of(Place first, Collection<? extends Place> more) {
        Set<Place> all = new LinkedHashSet<>();
        all.add(Objects.requireNonNull(first, "first"));
        for (Place p : Objects.requireNonNull(more, "more")) all.add(Objects.requireNonNull(p, "a place in more"));
        return new Places(List.copyOf(all));
    }

    /** These places and {@code more}, each once. */
    public Places and(Place... more) {
        return and(List.of(more));
    }

    /** These places and every one in {@code more}, each once. */
    public Places and(Collection<? extends Place> more) {
        Objects.requireNonNull(more, "more");
        if (more.isEmpty()) return this;
        return of(places.get(0), concat(places.subList(1, places.size()), more));
    }

    private static List<Place> concat(List<Place> a, Collection<? extends Place> b) {
        List<Place> out = new java.util.ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    @Override
    public Place get(int index) {
        return places.get(index);
    }

    @Override
    public int size() {
        return places.size();
    }
}
