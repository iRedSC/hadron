package dev.iredsc.hadron.harness;

import java.util.ArrayDeque;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The last {@link #KEPT} events a test's listeners heard, and how many came before them, so a long test can't flood
 * its report. Events may be heard on any thread that fires one; the watchdog reads it from its own.
 */
final class RecentEvents {
    /** Events a failure's report shows at most. */
    static final int KEPT = 30;

    private final ArrayDeque<FailureContext.Heard> kept = new ArrayDeque<>(KEPT);
    private int omitted;

    synchronized void add(int tick, String event, @Nullable Boolean cancelled, String line) {
        if (kept.size() == KEPT) {
            kept.removeFirst();
            omitted++;
        }
        kept.addLast(new FailureContext.Heard(tick, event, cancelled, line));
    }

    /** The events kept, oldest first, and how many earlier ones weren't, read together. */
    record Tail(List<FailureContext.Heard> events, int omitted) {}

    synchronized Tail tail() {
        return new Tail(List.copyOf(kept), omitted);
    }
}
