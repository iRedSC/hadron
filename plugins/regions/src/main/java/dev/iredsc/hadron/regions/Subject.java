package dev.iredsc.hadron.regions;

import java.util.Locale;
import java.util.Optional;

/**
 * Whom a region's rules are written for, as {@code rules:} keys and in commands. Which one an act is judged as is
 * {@code Who.subjectIn}: the same person is {@link #MEMBERS} in their own region and {@link #EVERYONE} next door.
 */
enum Subject {
    /** Owners and members: owning only adds managing. When nothing matches, allowed. */
    MEMBERS,
    /** Anyone else, online or not, and an outsider's machine. When nothing matches, the region doesn't decide. */
    EVERYONE,
    /** Nobody behind it: nature, or a machine or mob nobody owns. When nothing matches, the home rule decides. */
    WORLD,
    /** A mod acted and nothing said who. When nothing matches, refused. */
    UNKNOWN;

    String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    static Optional<Subject> parse(String key) {
        for (Subject s : values()) if (s.key().equals(key)) return Optional.of(s);
        return Optional.empty();
    }

    /** {@code members, everyone, world, unknown}. */
    static String list() {
        StringBuilder out = new StringBuilder();
        for (Subject s : values()) {
            if (out.length() > 0) out.append(", ");
            out.append(s.key());
        }
        return out.toString();
    }
}
