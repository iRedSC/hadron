package dev.iredsc.hadron.neoforge;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Which tests a run is limited to, as Gradle's {@code --tests} reads: each pattern names a class ({@code HelloTests}
 * or its full name) or a method ({@code HelloTests.helloGreetsTheNameGiven}), and {@code *} matches anything, so
 * {@code HelloTests.hello*} or {@code *secret*} work too, in any case. A test runs when any pattern matches it. No patterns means
 * every test.
 */
record TestFilter(List<String> patterns, List<Pattern> compiled) {
    static final TestFilter ALL = new TestFilter(List.of(), List.of());

    /** Patterns separated by commas or new lines; blanks are ignored. */
    static TestFilter parse(String spec) {
        if (spec == null) return ALL;
        List<String> patterns = new ArrayList<>();
        List<Pattern> compiled = new ArrayList<>();
        for (String part : spec.split("[,\\n]")) {
            String p = part.strip();
            if (p.isEmpty()) continue;
            patterns.add(p);
            compiled.add(glob(p));
        }
        return patterns.isEmpty() ? ALL : new TestFilter(List.copyOf(patterns), List.copyOf(compiled));
    }

    boolean all() {
        return patterns.isEmpty();
    }

    /** Whether the test {@code method} of {@code type} runs; a null method asks about the class as a whole. */
    boolean matches(Class<?> type, String method) {
        if (all()) return true;
        String simple = type.getSimpleName();
        String full = type.getName();
        for (Pattern p : compiled) {
            if (p.matcher(simple).matches() || p.matcher(full).matches()) return true;
            if (method != null && (p.matcher(simple + "." + method).matches() || p.matcher(full + "." + method).matches())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a test class that couldn't load may hold tests this filter asks for, so its failure is reported: true
     * unless no pattern could name it or one of its methods.
     */
    boolean mightMatchClass(String fullName) {
        if (all()) return true;
        String simple = fullName.substring(Math.max(fullName.lastIndexOf('.'), fullName.lastIndexOf('$')) + 1);
        for (int i = 0; i < patterns.size(); i++) {
            String p = patterns.get(i);
            if (compiled.get(i).matcher(simple).matches() || compiled.get(i).matcher(fullName).matches()) return true;
            if (p.contains("*") || p.startsWith(simple + ".") || p.startsWith(fullName + ".")) return true;
        }
        return false;
    }

    private static Pattern glob(String pattern) {
        StringBuilder regex = new StringBuilder();
        String[] pieces = pattern.split("\\*", -1);
        for (int i = 0; i < pieces.length; i++) {
            if (i > 0) regex.append(".*");
            if (!pieces[i].isEmpty()) regex.append(Pattern.quote(pieces[i]));
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }
}
