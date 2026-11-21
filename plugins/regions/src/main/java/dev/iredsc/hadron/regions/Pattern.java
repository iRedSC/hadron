package dev.iredsc.hadron.regions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;

/**
 * What a rule names: a flag ({@code build}), or a node pattern ({@code entity.hurt.animal}, {@code entity.hurt},
 * {@code entity.*.animal}). A pattern covers every node below it, and {@code *} stands for exactly one segment
 * anywhere but the first. Made only by {@link #parse}, which checks each segment against the node tree, or from a
 * {@link Bundle} or {@link Node} in code, so no rule can hold a pattern that matches nothing.
 */
final class Pattern implements Comparable<Pattern> {
    private static final String WILD = "*";

    private final String text;
    private final Optional<Bundle> bundle;
    private final List<String> segments;

    private Pattern(String text, Optional<Bundle> bundle, List<String> segments) {
        this.text = text;
        this.bundle = bundle;
        this.segments = List.copyOf(segments);
    }

    /** Why some text isn't a pattern, said so the reader knows what to write instead. */
    static final class Invalid extends Exception {
        private static final long serialVersionUID = 1L;

        Invalid(String message) {
            super(message, null, false, false);
        }
    }

    static Pattern of(Bundle bundle) {
        if (bundle == Bundle.OPEN) throw new IllegalArgumentException("open is no flag a rule can name");
        return new Pattern(bundle.key(), Optional.of(bundle), List.of());
    }

    static Pattern of(Node node) {
        return new Pattern(node.key(), Optional.empty(), node.segments());
    }

    /** {@code entity.*.<kind>}: anything done to an entity of this kind. */
    static Pattern everyActOn(Kind kind) {
        return new Pattern("entity." + WILD + "." + kind.key(), Optional.empty(), List.of("entity", WILD, kind.key()));
    }

    /** The one way text becomes a pattern. */
    static Pattern parse(String text) throws Invalid {
        if (text.isEmpty()) {
            throw new Invalid("an empty name; write a node such as block.break, or a flag: " + Bundle.list());
        }
        if (!text.equals(text.toLowerCase(Locale.ROOT))) {
            throw new Invalid("`" + text + "` has capitals; nodes are lowercase: `" + text.toLowerCase(Locale.ROOT)
                    + "`");
        }
        Optional<Bundle> bundle = Bundle.named(text);
        if (bundle.isPresent()) return of(bundle.get());
        if (text.equals("open")) {
            throw new Invalid("`open` is no flag a rule can name: it is every node no flag holds. Name those nodes "
                    + "instead, such as entity.push");
        }
        List<String> segments = List.of(text.split("\\.", -1));
        if (segments.get(0).equals(WILD)) {
            throw new Invalid("`" + text + "` starts with *, which YAML would read as an alias: name the root, one "
                    + "of " + String.join(", ", children(List.of(), Node.all())));
        }
        for (String s : segments) {
            if (s.isEmpty()) {
                throw new Invalid("`" + text + "` has an empty segment: write each part between dots, such as "
                        + "block.break");
            }
        }
        List<Node> matched = Node.all();
        for (int i = 0; i < segments.size(); i++) {
            String seg = segments.get(i);
            List<Node> next = new ArrayList<>();
            for (Node n : matched) {
                List<String> ns = n.segments();
                if (ns.size() > i && (seg.equals(WILD) || ns.get(i).equals(seg))) next.add(n);
            }
            if (next.isEmpty()) throw new Invalid(noNode(text, segments.subList(0, i), seg, matched));
            matched = next;
        }
        return new Pattern(text, Optional.empty(), segments);
    }

    /** Names the deepest valid prefix and what is under it, with the nearest child when the segment looks mistyped. */
    private static String noNode(String text, List<String> prefix, String seg, List<Node> under) {
        String at = String.join(".", prefix);
        TreeSet<String> kids = children(prefix, under);
        if (kids.isEmpty()) return "`" + text + "` matches no node: " + at + " has nothing below it, so write `" + at + "`";
        String guess = "";
        if (!seg.equals(WILD)) {
            String best = null;
            int bestDistance = Integer.MAX_VALUE;
            for (String k : kids) {
                int d = distance(seg, k);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = k;
                }
            }
            if (best != null && bestDistance <= Math.max(2, seg.length() / 3)) {
                guess = " Did you mean `" + at + "." + best + "`?";
            }
        }
        return "`" + text + "` isn't a node." + guess + " Under " + at + ": " + String.join(", ", kids);
    }

    /** The distinct segments right after {@code prefix} among {@code nodes}, sorted. */
    private static TreeSet<String> children(List<String> prefix, List<Node> nodes) {
        TreeSet<String> out = new TreeSet<>();
        for (Node n : nodes) {
            List<String> ns = n.segments();
            if (ns.size() > prefix.size()) out.add(ns.get(prefix.size()));
        }
        return out;
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    /**
     * What to offer when {@code typed} is part of a pattern, one segment at a time: the flags and roots first, then
     * what is under the segments already typed (and {@code *}), each as the whole pattern so far.
     */
    static List<String> completions(String typed) {
        List<String> parts = List.of(typed.split("\\.", -1));
        List<String> prefix = parts.subList(0, parts.size() - 1);
        String last = parts.get(parts.size() - 1);
        TreeSet<String> next = new TreeSet<>();
        for (Node n : Node.all()) {
            List<String> ns = n.segments();
            if (ns.size() <= prefix.size()) continue;
            boolean fits = true;
            for (int i = 0; i < prefix.size(); i++) {
                if (!prefix.get(i).equals(WILD) && !prefix.get(i).equals(ns.get(i))) fits = false;
            }
            if (fits) next.add(ns.get(prefix.size()));
        }
        if (!prefix.isEmpty() && !next.isEmpty()) next.add(WILD);
        String head = prefix.isEmpty() ? "" : String.join(".", prefix) + ".";
        List<String> out = new ArrayList<>();
        if (prefix.isEmpty()) {
            for (Bundle b : Bundle.values()) if (b != Bundle.OPEN && b.key().startsWith(last)) out.add(b.key());
        }
        for (String s : next) if (s.startsWith(last)) out.add(head + s);
        return out;
    }

    /** The flag this names, if it names one. */
    Optional<Bundle> bundle() {
        return bundle;
    }

    /** Whether it covers {@code node}. */
    boolean matches(Node node) {
        if (bundle.isPresent()) return node.bundle() == bundle.get();
        List<String> ns = node.segments();
        if (ns.size() < segments.size()) return false;
        for (int i = 0; i < segments.size(); i++) {
            if (!segments.get(i).equals(WILD) && !segments.get(i).equals(ns.get(i))) return false;
        }
        return true;
    }

    /**
     * How many segments it names outright; a flag counts as 0, so anything named beats a flag, a root alone included
     * (a pattern can't start with {@code *}, so it names at least its root).
     */
    int specificity() {
        if (bundle.isPresent()) return 0;
        int n = 0;
        for (String s : segments) if (!s.equals(WILD)) n++;
        return n;
    }

    /** The one node it names, if it names exactly one leaf. */
    Optional<Node> leaf() {
        if (bundle.isPresent() || segments.contains(WILD)) return Optional.empty();
        for (Node n : Node.all()) if (n.segments().equals(segments)) return Optional.of(n);
        return Optional.empty();
    }

    /** Flags first, then by text: the order rules are saved and shown in. */
    @Override
    public int compareTo(Pattern o) {
        if (bundle.isPresent() != o.bundle.isPresent()) return bundle.isPresent() ? -1 : 1;
        if (bundle.isPresent()) return bundle.get().compareTo(o.bundle.get());
        return text.compareTo(o.text);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Pattern p && p.text.equals(text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }

    @Override
    public String toString() {
        return text;
    }
}
