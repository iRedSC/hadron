package dev.iredsc.hadron.regions;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Whether the regions at one spot let an act through. Plain functions over {@link Region}s, so every rule is checked on
 * the JVM without a server.
 *
 * <ol>
 *   <li>Highest priority first: every region at that priority answers.
 *   <li>Within a region, its most specific matching rule for the subject, deny on ties ({@link Rules#match}); with
 *       none, the subject's own answer: members allowed, everyone abstains, the world by the home rule, unknown
 *       refused.
 *   <li>At one priority, any region refusing refuses, else any allowing allows, else the next priority down decides.
 *   <li>If every region abstains, it is allowed: no region forbade it.
 * </ol>
 */
final class Resolve {
    private Resolve() {}

    /** What decided one region's answer: a rule it names, or the subject's own answer when none matches. */
    sealed interface Rule {
        Rules.Verdict verdict();

        record Named(Pattern pattern, Rules.Verdict verdict) implements Rule {}

        /** The subject's own answer, and why in a few words: {@code from outside}, {@code a member}. */
        record Implicit(Rules.Verdict verdict, String why) implements Rule {}
    }

    /** One region's answer for one node and subject. */
    record Decision(Region region, Subject subject, Node node, Rule rule) {
        boolean refuses() {
            return rule.verdict() == Rules.Verdict.DENY;
        }

        /** As a refused operator or a watcher sees it: {@code spawn · everyone · entity.hurt.animal (build)}. */
        String line() {
            String head = region.id() + " · " + subject.key() + " · " + node.key();
            return switch (rule) {
                case Rule.Named n -> n.pattern().toString().equals(node.key()) ? head : head + " (" + n.pattern() + ")";
                case Rule.Implicit i -> head + " · " + i.why();
            };
        }
    }

    /** The region's own answer, or empty when it abstains (everyone, with no rule matching). */
    static Optional<Decision> answer(Region region, Who who, Node node) {
        return answer(region, who.subjectIn(region), node, who instanceof Who.World w && w.homeInside(region));
    }

    /**
     * The region's answer for {@code subject}; {@code homeInside} says, for the world, whether it sets out from inside
     * the region. {@code /region why} asks this way, for the world as if it acted from where they stand.
     */
    static Optional<Decision> answer(Region region, Subject subject, Node node, boolean homeInside) {
        Optional<Rules.Match> named = region.rules().match(subject, node);
        if (named.isPresent()) {
            return Optional.of(new Decision(region, subject, node, new Rule.Named(named.get().pattern(),
                    named.get().verdict())));
        }
        Rule implicit = switch (subject) {
            case MEMBERS -> new Rule.Implicit(Rules.Verdict.ALLOW, "a member");
            case EVERYONE -> null;
            case WORLD -> homeInside ? new Rule.Implicit(Rules.Verdict.ALLOW, "its own")
                    : new Rule.Implicit(Rules.Verdict.DENY, "from outside");
            case UNKNOWN -> new Rule.Implicit(Rules.Verdict.DENY, "unknown, refused until a bridge says who");
        };
        return Optional.ofNullable(implicit).map(r -> new Decision(region, subject, node, r));
    }

    /**
     * The decision refusing {@code who} {@code node} where exactly {@code here} apply, or empty when allowed. Regions
     * in {@code alreadyIn} never refuse: anyone inside a region can walk out, or about within it.
     */
    static Optional<Decision> refusing(Collection<Region> here, Who who, Node node, Collection<Region> alreadyIn) {
        TreeSet<Integer> levels = new TreeSet<>();
        for (Region r : here) levels.add(r.priority());
        for (int level : levels.descendingSet()) {
            Decision allowed = null;
            Decision denied = null;
            for (Region r : here) {
                if (r.priority() != level) continue;
                if (alreadyIn.contains(r)) {
                    allowed = new Decision(r, who.subjectIn(r), node, new Rule.Implicit(Rules.Verdict.ALLOW,
                            "already inside"));
                    continue;
                }
                Optional<Decision> d = answer(r, who, node);
                if (d.isEmpty()) continue;
                if (d.get().refuses()) {
                    if (denied == null) denied = d.get();
                } else {
                    allowed = d.get();
                }
            }
            if (denied != null) return Optional.of(denied);
            if (allowed != null) return Optional.empty();
        }
        return Optional.empty();
    }

    static Optional<Decision> refusing(Collection<Region> here, Who who, Node node) {
        return refusing(here, who, node, List.of());
    }

    /** The first refusal among several spots, each being the regions there. */
    static Optional<Decision> refusingAny(Collection<? extends Collection<Region>> spots, Who who, Node node) {
        for (Collection<Region> here : spots) {
            Optional<Decision> no = refusing(here, who, node);
            if (no.isPresent()) return no;
        }
        return Optional.empty();
    }
}
