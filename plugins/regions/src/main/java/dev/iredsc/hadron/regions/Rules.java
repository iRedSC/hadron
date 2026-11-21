package dev.iredsc.hadron.regions;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * What one region grants and denies, per {@link Subject}: lists of {@link Pattern}s only, so nothing unchecked
 * reaches a decision. Immutable; each list is sorted (flags first) and a pattern is never both allowed and denied for
 * one subject.
 */
record Rules(Map<Subject, Rules.Lists> bySubject) {
    /** Whether a rule lets it through or refuses it. */
    enum Verdict {
        ALLOW,
        DENY;

        String key() {
            return this == ALLOW ? "allow" : "deny";
        }
    }

    /** One subject's rules. */
    record Lists(List<Pattern> allow, List<Pattern> deny) {
        Lists {
            allow = List.copyOf(new TreeSet<>(allow));
            deny = List.copyOf(new TreeSet<>(deny));
            for (Pattern p : allow) {
                if (deny.contains(p)) throw new IllegalArgumentException(p + " is both allowed and denied");
            }
        }

        static final Lists EMPTY = new Lists(List.of(), List.of());

        boolean isEmpty() {
            return allow.isEmpty() && deny.isEmpty();
        }

        List<Pattern> of(Verdict v) {
            return v == Verdict.ALLOW ? allow : deny;
        }
    }

    /** The rule deciding one node: the pattern named and whether it allows. */
    record Match(Pattern pattern, Verdict verdict) {}

    Rules {
        Map<Subject, Lists> kept = new EnumMap<>(Subject.class);
        for (Map.Entry<Subject, Lists> e : bySubject.entrySet()) {
            if (!e.getValue().isEmpty()) kept.put(e.getKey(), e.getValue());
        }
        bySubject = kept.isEmpty() ? Map.of() : Map.copyOf(kept);
    }

    /** No rules: a region that only marks an area. */
    static Rules none() {
        return new Rules(Map.of());
    }

    /**
     * What {@code /region define} writes into a new region: everyone else may neither build nor use, and the world may
     * act on players, so a monster that wandered in can still hurt them. Written into the file, never built into the
     * code, so a later version can't quietly change an existing region.
     */
    static Rules fresh() {
        return none().with(Subject.EVERYONE, Pattern.of(Bundle.BUILD), Optional.of(Verdict.DENY))
                .with(Subject.EVERYONE, Pattern.of(Bundle.USE), Optional.of(Verdict.DENY))
                .with(Subject.WORLD, Pattern.everyActOn(Kind.PLAYER), Optional.of(Verdict.ALLOW));
    }

    Lists of(Subject subject) {
        return bySubject.getOrDefault(subject, Lists.EMPTY);
    }

    /**
     * The rule deciding {@code node} for {@code subject}: the most specific pattern matching it, and at equal
     * specificity a deny. Empty when none matches, and the subject's own answer decides.
     */
    Optional<Match> match(Subject subject, Node node) {
        Lists lists = of(subject);
        Match best = null;
        for (Verdict v : List.of(Verdict.DENY, Verdict.ALLOW)) {
            for (Pattern p : lists.of(v)) {
                if (!p.matches(node)) continue;
                if (best == null || p.specificity() > best.pattern().specificity()) best = new Match(p, v);
            }
        }
        return Optional.ofNullable(best);
    }

    /** {@code pattern} allowed, denied, or (empty) neither, for {@code subject}. */
    Rules with(Subject subject, Pattern pattern, Optional<Verdict> verdict) {
        Objects.requireNonNull(pattern, "pattern");
        Lists old = of(subject);
        List<Pattern> allow = new ArrayList<>(old.allow());
        List<Pattern> deny = new ArrayList<>(old.deny());
        allow.remove(pattern);
        deny.remove(pattern);
        verdict.ifPresent(v -> (v == Verdict.ALLOW ? allow : deny).add(pattern));
        Map<Subject, Lists> next = new EnumMap<>(Subject.class);
        next.putAll(bySubject);
        next.put(subject, new Lists(allow, deny));
        return new Rules(next);
    }

    /**
     * {@code /region flag}: the flag allowed, denied or (empty) cleared for everyone, and for {@code pvp} for members
     * too, so a no-PvP region binds members.
     */
    Rules withFlag(Bundle flag, Optional<Verdict> verdict) {
        Rules next = with(Subject.EVERYONE, Pattern.of(flag), verdict);
        return flag == Bundle.PVP ? next.with(Subject.MEMBERS, Pattern.of(flag), verdict) : next;
    }
}
