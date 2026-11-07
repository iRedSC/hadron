package dev.iredsc.hadron.neoforge;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * What a test run found, written as plain text an agent reads top to bottom: a line per test, the failures in full,
 * and a summary. Two kinds of line are also for machines, and keep their shape: {@code SUMMARY <jar> ...: N passed, M
 * failed} per suite, and the last line, {@code RESULT: PASSED} or {@code RESULT: FAILED}.
 *
 * <p>Written by the runner when it finishes, and by the watchdog if a test hangs, so it's synchronized.
 */
final class TestReport {
    enum Status { PASS, FAIL, HUNG }

    record Result(String id, Status status, String location, String message, String detail, int ticks, long millis) {}

    static final class Suite {
        final String jar;
        final String plugin;
        final List<Result> results = new ArrayList<>();
        /** Why none of its tests could run, or null. */
        String broken;

        Suite(String jar, String plugin) {
            this.jar = jar;
            this.plugin = plugin;
        }
    }

    private final List<Suite> suites = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();
    private final long started = System.nanoTime();

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
            int p = 0, f = 0;
            for (Result r : s.results) {
                if (r.status() == Status.PASS) p++;
                else f++;
            }
            if (s.broken != null) f++;
            passed += p;
            failed += f;
            out.append("SUMMARY ").append(s.jar).append(" (").append(s.plugin).append("): ").append(p)
                    .append(" passed, ").append(f).append(" failed\n");
        }
        if (suites.isEmpty()) {
            out.append("No test suites ran: no jar in plugins/ has META-INF/hadron-tests.txt. Run tests with "
                    + "./gradlew hadronTest, which builds that jar from src/test/java.\n");
        }
        out.append(passed).append(" passed, ").append(failed).append(" failed in ")
                .append(seconds((System.nanoTime() - started) / 1_000_000)).append('\n');
        out.append("RESULT: ").append(passed() ? "PASSED" : "FAILED").append('\n');
        return out.toString();
    }

    synchronized void write(File file) {
        try {
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null) parent.mkdirs();
            Files.writeString(file.toPath(), render(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Hadron couldn't write the test report to " + file + ": " + e);
        }
    }

    private static String seconds(long millis) {
        return String.format("%.1f s", millis / 1000.0);
    }

    private static String indent(String text) {
        return text == null ? "" : text.strip().replace("\n", "\n    ");
    }
}
