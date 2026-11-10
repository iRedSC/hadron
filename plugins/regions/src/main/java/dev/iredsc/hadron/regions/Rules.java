package dev.iredsc.hadron.regions;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Whether regions allow something. Plain functions over the regions at a place, so every rule is checked on the JVM
 * without a server.
 */
final class Rules {
    private Rules() {}

    /**
     * Whether {@code who} may do {@code flag} where exactly {@code here} apply. Only the highest priority among them
     * decides, and any of those denying denies. Empty {@code who} is an outsider to every region: a machine with no
     * owner, or a mod nobody bridged.
     *
     * @return the region that refuses, or empty when allowed.
     */
    static Optional<Region> refusing(Collection<Region> here, Flag flag, Optional<UUID> who) {
        return refusing(here, flag, who, List.of());
    }

    /** As {@link #refusing(Collection, Flag, Optional)}, but regions in {@code ignored} never refuse. */
    private static Optional<Region> refusing(Collection<Region> here, Flag flag, Optional<UUID> who,
            Collection<Region> ignored) {
        if (here.isEmpty()) return Optional.empty();
        int top = Integer.MIN_VALUE;
        for (Region r : here) top = Math.max(top, r.priority());
        for (Region r : here) {
            if (r.priority() != top || ignored.contains(r)) continue;
            if (flag.membersExempt && who.isPresent() && r.isMember(who.get())) continue;
            if (!r.flag(flag).orElse(flag.outsiderDefault)) return Optional.of(r);
        }
        return Optional.empty();
    }

    /** The first region refusing {@code who} {@code flag} at any of {@code places}, each place being the regions there. */
    static Optional<Region> refusingAny(Collection<? extends Collection<Region>> places, Flag flag,
            Optional<UUID> who) {
        for (Collection<Region> here : places) {
            Optional<Region> no = refusing(here, flag, who);
            if (no.isPresent()) return no;
        }
        return Optional.empty();
    }

    /**
     * Whether the world on its own may carry a change from {@code source} into {@code target}: water flowing, a piston
     * pushing, grass spreading. It may within the regions it starts in, and into any region that lets outsiders build;
     * so a piston outside can't push into spawn, and a member's piston door inside it still works. Only the
     * highest priority at the target decides, as for a player building there.
     *
     * @return the region it would cross into and that refuses, or empty when allowed.
     */
    static Optional<Region> refusingCrossing(Collection<Region> source, Collection<Region> target) {
        // As for anyone building: only the highest priority at the target decides.
        int top = Integer.MIN_VALUE;
        for (Region r : target) top = Math.max(top, r.priority());
        for (Region r : target) {
            if (r.priority() != top || source.contains(r)) continue;
            if (!r.flag(Flag.BUILD).orElse(Flag.BUILD.outsiderDefault)) return Optional.of(r);
        }
        return Optional.empty();
    }

    /**
     * Whether something moving from {@code from} to {@code to} may enter. Only regions it isn't already in can refuse,
     * so someone inside a region when it was made, or when they joined, can always walk out.
     */
    static Optional<Region> refusingEntry(Collection<Region> from, Collection<Region> to, Optional<UUID> who) {
        if (from.containsAll(to)) return Optional.empty();
        return refusing(to, Flag.ENTRY, who, from);
    }
}
