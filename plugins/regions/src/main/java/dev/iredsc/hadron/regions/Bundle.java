package dev.iredsc.hadron.regions;

import java.util.Locale;
import java.util.Optional;

/**
 * Which flag holds a node: each of the four flags is exactly the set of nodes that name it ({@link Action}), so a node
 * added later lands in its flag and is protected from the day it exists. {@link #OPEN} is no flag: a node no bundle
 * holds is allowed unless a region denies it by name.
 */
enum Bundle {
    /** Placing, breaking and changing blocks; doing things to animals and objects; the world's own changes. */
    BUILD(false),
    /** Right-clicking blocks and entities, inventories, items and resources. */
    USE(false),
    /** A player ending up inside: walking, carried, teleported, seated, set down. */
    ENTRY(false),
    /** Players hurting players, judged at both ends: a safe zone keeps hits out and keeps its own from leaving. */
    PVP(true),
    /** No flag holds it. Can't be named in a rule. */
    OPEN(false);

    private final boolean bothEnds;

    Bundle(boolean bothEnds) {
        this.bothEnds = bothEnds;
    }

    /**
     * Whether its nodes are decided where the act set out from as well as where it happens, when the event states
     * where it set out from.
     */
    boolean bothEnds() {
        return bothEnds;
    }

    /** As written in regions.yml and commands; {@code open} is never written. */
    String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The bundle a rule can name: every one but {@link #OPEN}. */
    static Optional<Bundle> named(String key) {
        for (Bundle b : values()) {
            if (b != OPEN && b.key().equals(key)) return Optional.of(b);
        }
        return Optional.empty();
    }

    /** The bundles a rule can name, as text: {@code build, use, entry, pvp}. */
    static String list() {
        StringBuilder out = new StringBuilder();
        for (Bundle b : values()) {
            if (b == OPEN) continue;
            if (out.length() > 0) out.append(", ");
            out.append(b.key());
        }
        return out.toString();
    }
}
