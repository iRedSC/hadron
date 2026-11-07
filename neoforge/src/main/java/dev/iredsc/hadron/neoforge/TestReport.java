package dev.iredsc.hadron.neoforge;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * What a test run found, written twice: as plain text an agent reads top to bottom (a line per test, the failures in
 * full, and a summary), and as JSON beside it for tools ({@code report.json}; its schema is in docs/testing.md). Two
 * kinds of text line are also for machines, and keep their shape: {@code SUMMARY <jar> ...: N passed, M failed} per
 * suite, and the last line, {@code RESULT: PASSED} or {@code RESULT: FAILED}.
 *
 * <p>Written by the runner when it finishes, and by the watchdog if a test hangs, so it's synchronized.
 */
final class TestReport {
    /** Bumped when a field changes meaning or goes away; new fields don't bump it. */
    static final int SCHEMA = 1;

    enum Status { PASS, FAIL, HUNG }

    /**
     * One test's outcome.
     *
     * @param id        {@code SimpleClass.method}, or the class name alone when the class itself couldn't load
     * @param className the test class's full name
     * @param method    the method's name, or null when the class couldn't load
     * @param source    where the test is: {@code HelloTests.java:12}, its first line; null if the class has no lines
     * @param location  the test's own line nearest the failure, or null
     * @param detail    the stack, or where the server thread was for a hung test; null for an assertion
     */
    record Result(String id, String className, String method, Status status, String source, String location,
                  String message, String detail, int ticks, long millis) {}

    static final class Suite {
        final String jar;
        final String plugin;
        final List<Result> results = new ArrayList<>();
        /** Why none of its tests could run, or null. */
        String broken;
        /** The patterns this run was limited to, or empty for every test. */
        List<String> filter = List.of();
        /** How many tests the suite has, before the filter. */
        int available;

        Suite(String jar, String plugin) {
            this.jar = jar;
            this.plugin = plugin;
        }
    }

    private final List<Suite> suites = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();
    private final long started = System.nanoTime();
    private final Instant startedAt = Instant.now();
    /** "cold" for a one-off run; "watch" for a warm server's rerun, numbered from 1. */
    private final String mode;
    private final int run;

    TestReport() {
        this("cold", 1);
    }

    TestReport(String mode, int run) {
        this.mode = mode;
        this.run = run;
    }

    synchronized Suite suite(String jar, String plugin) {
        Suite s = new Suite(jar, plugin);
        suites.add(s);
        return s;
    }

    synchronized void add(Suite suite, Result result) {
        suite.results.add(result);
    }

    synchronized void broken(Suite suite, String why) {
        suite.broken = why;
    }

    /** A failure of the run itself, outside any test. */
    synchronized void problem(String what) {
        problems.add(what);
    }

    synchronized boolean passed() {
        if (!problems.isEmpty() || suites.isEmpty()) return false;
        for (Suite s : suites) {
            if (s.broken != null) return false;
            for (Result r : s.results) if (r.status() != Status.PASS) return false;
        }
        return true;
    }

    synchronized String render() {
        StringBuilder out = new StringBuilder("Hadron test report\n");
        for (Suite s : suites) {
            out.append('\n').append(s.plugin).append(" (").append(s.jar).append(")\n");
            if (!s.filter.isEmpty()) {
                out.append("Only tests matching ").append(String.join(", ", s.filter)).append(": ")
                        .append(s.results.size()).append(" of ").append(s.available).append('\n');
            }
            if (s.broken != null) out.append("BROKEN ").append(s.broken).append('\n');
            for (Result r : s.results) {
                out.append(r.status()).append(' ').append(r.id());
                if (r.status() == Status.PASS) {
                    out.append("  (").append(r.ticks()).append(r.ticks() == 1 ? " tick, " : " ticks, ").append(seconds(r.millis())).append(")");
                } else if (r.location() != null) {
                    out.append("  (").append(r.location()).append(')');
                }
                out.append('\n');
            }
        }
        boolean anyFailure = false;
        for (Suite s : suites) for (Result r : s.results) anyFailure |= r.status() != Status.PASS;
        if (anyFailure || !problems.isEmpty()) {
            out.append("\nFailures\n");
            for (String p : problems) out.append("\nRUN ").append(indent(p)).append('\n');
            for (Suite s : suites) {
                for (Result r : s.results) {
                    if (r.status() == Status.PASS) continue;
                    out.append('\n').append(r.status()).append(' ').append(r.id());
                    if (r.location() != null) out.append("  (").append(r.location()).append(')');
                    out.append("\n    ").append(indent(r.message())).append('\n');
                    if (r.detail() != null && !r.detail().isBlank()) out.append("    ").append(indent(r.detail())).append('\n');
                }
            }
        }
        out.append("\nSummary\n");
        int passed = 0, failed = 0;
        for (Suite s : suites) {
            Counts c = counts(s);
            passed += c.passed;
            failed += c.failed();
            out.append("SUMMARY ").append(s.jar).append(" (").append(s.plugin).append("): ").append(c.passed)
                    .append(" passed, ").append(c.failed()).append(" failed\n");
        }
        if (suites.isEmpty()) {
            out.append("No test suites ran: no jar in plugins/ has META-INF/hadron-tests.txt. Run tests with "
                    + "./gradlew hadronTest, which builds that jar from src/test/java.\n");
        }
        out.append(passed).append(" passed, ").append(failed).append(" failed in ")
                .append(seconds(elapsedMillis())).append('\n');
        out.append("RESULT: ").append(passed() ? "PASSED" : "FAILED").append('\n');
        return out.toString();
    }

    /** The same report for tools. Every key is always present; a value that doesn't apply is null. */
    synchronized String json() {
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        root.addProperty("result", passed() ? "PASSED" : "FAILED");
        root.addProperty("mode", mode);
        root.addProperty("run", run);
        root.addProperty("startedAt", startedAt.toString());
        root.addProperty("wallMillis", elapsedMillis());
        Counts total = new Counts();
        JsonArray suiteArray = new JsonArray();
        for (Suite s : suites) {
            Counts c = counts(s);
            total.add(c);
            JsonObject suite = new JsonObject();
            suite.addProperty("suite", s.jar);
            suite.addProperty("plugin", s.plugin);
            suite.add("broken", s.broken == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(s.broken));
            JsonArray filter = new JsonArray();
            for (String f : s.filter) filter.add(f);
            suite.add("filter", filter);
            suite.addProperty("available", s.available);
            suite.add("summary", c.json());
            JsonArray tests = new JsonArray();
            for (Result r : s.results) {
                JsonObject t = new JsonObject();
                t.addProperty("suite", s.jar);
                t.addProperty("id", r.id());
                t.addProperty("class", r.className());
                t.addProperty("method", r.method());
                t.addProperty("status", r.status().name());
                t.addProperty("ticks", r.ticks());
                t.addProperty("wallMillis", r.millis());
                t.addProperty("message", r.message());
                t.addProperty("source", r.source());
                t.addProperty("failedAt", r.location());
                t.addProperty("stack", r.detail() == null || r.detail().isBlank() ? null : r.detail());
                tests.add(t);
            }
            suite.add("tests", tests);
            suiteArray.add(suite);
        }
        JsonObject summary = total.json();
        summary.addProperty("suites", suites.size());
        root.add("summary", summary);
        JsonArray problemArray = new JsonArray();
        for (String p : problems) problemArray.add(p);
        root.add("problems", problemArray);
        root.add("suites", suiteArray);
        return new GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n";
    }

    private static final class Counts {
        int passed, failed, hung, broken;

        int failed() {
            return failed + hung + broken;
        }

        void add(Counts c) {
            passed += c.passed;
            failed += c.failed;
            hung += c.hung;
            broken += c.broken;
        }

        JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("total", passed + failed + hung);
            o.addProperty("passed", passed);
            o.addProperty("failed", failed);
            o.addProperty("hung", hung);
            o.addProperty("broken", broken);
            return o;
        }
    }

    private static Counts counts(Suite s) {
        Counts c = new Counts();
        for (Result r : s.results) {
            switch (r.status()) {
                case PASS -> c.passed++;
                case FAIL -> c.failed++;
                case HUNG -> c.hung++;
            }
        }
        if (s.broken != null) c.broken++;
        return c;
    }

    private long elapsedMillis() {
        return (System.nanoTime() - started) / 1_000_000;
    }

    /** Writes the text report to {@code file} and the JSON one beside it, as {@code report.json}. */
    synchronized void write(File file) {
        try {
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null) parent.mkdirs();
            Files.writeString(file.toPath(), render(), StandardCharsets.UTF_8);
            Files.writeString(jsonFile(file).toPath(), json(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Hadron couldn't write the test report to " + file + ": " + e);
        }
    }

    /** {@code report.txt}'s JSON twin: {@code report.json} in the same folder. */
    static File jsonFile(File text) {
        String name = text.getName();
        String base = name.endsWith(".txt") ? name.substring(0, name.length() - 4) : name;
        return new File(text.getAbsoluteFile().getParentFile(), base + ".json");
    }

    private static String seconds(long millis) {
        return String.format("%.1f s", millis / 1000.0);
    }

    private static String indent(String text) {
        return text == null ? "" : text.strip().replace("\n", "\n    ");
    }
}
